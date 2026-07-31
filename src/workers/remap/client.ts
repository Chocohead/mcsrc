import * as Comlink from "comlink";
import { openJar } from "../../utils/Jar";
import { type ClassFilePath, classNameFromClassFilePath, isClassFilePath, toClassFilePath } from "../../utils/Names";
import { writeZip } from "./zip";
import type { RemapClassJob, RemapWorker, RemapWorkerResult, RemapWorkerStats } from "./worker";
import WorkPool from "./marshaller";

const batchSize = 8;
const maxWorkers = 8;

function createWorker() {
    const worker = new Worker(new URL("./worker.ts", import.meta.url), { type: "module", name: "jar-remapper" });
    return {
        c: Comlink.wrap<RemapWorker>(worker),
        w: worker,
    };
}

export async function remapMinecraftJar(
    version: string,
    jarBlob: Blob,
    mappingsBlob: Blob,
    onProgress?: (percent: number) => void,
): Promise<Blob> {
    const startTime = performance.now();
    const threads = Math.max(1, Math.min(maxWorkers, (navigator.hardwareConcurrency || 4) - 1));
    const workers = Array.from({ length: threads }, () => createWorker());

    try {
        const jar = await openJar(version, jarBlob);
        const classMapStartTime = performance.now();
        const classMapLoadMs = performance.now() - classMapStartTime;
        const classPaths = Object.keys(jar.entries).filter(isClassFilePath);

        if (classPaths.length === 0) {
            return writeZip([]);
        }

        onProgress?.(0);

        const remapIndexStartTime = performance.now();
        await Promise.all(workers.map(worker => worker.c.setJar(version, jarBlob)));
        let completion; {
            let indexed = 0;
            const pool = new WorkPool(workers, (worker, batch: ClassFilePath[]) => {
                const index = worker.c.buildRemapIndex(batch);
                if (onProgress) {
                    indexed += batch.length;
                    onProgress(Math.round((indexed / classPaths.length) * 50));
                }
                return index;
            });
            completion = pool.completion();
            for (let slice = 0; slice < classPaths.length; slice += batchSize) {
                pool.queue(classPaths.slice(slice, slice + batchSize));
            }
        }
        const remapIndexResults = await Promise.all(await completion);
        const remapIndex = await workers[0].c.propagateMappings(remapIndexResults.flat(), mappingsBlob);
        await Promise.all(workers.slice(1).map(worker => worker.c.loadMappings(remapIndex)));
        const remapIndexMs = performance.now() - remapIndexStartTime;

        onProgress?.(50);

        {
            let remapped = 0;
            const pool = new WorkPool(workers, (worker, batch: RemapClassJob[]) => {
                const result = worker.c.remapClasses(batch);
                if (onProgress) {
                    remapped += batch.length;
                    onProgress(50 + Math.round((remapped / classPaths.length) * 50));
                }
                return result;
            });
            completion = pool.completion();
            for (let slice = 0; slice < classPaths.length; slice += batchSize) {
                pool.queue(classPaths.slice(slice, slice + batchSize).map(sourcePath => {
                    const className = classNameFromClassFilePath(sourcePath);
                    const mappedClassName = remapIndex.get(className)?.newName ?? className;
                    const targetPath = toClassFilePath(mappedClassName);
                    return { sourcePath, targetPath };
                }));
            }
        }
        const workerResults = await Promise.all(await completion);

        const timings = mergeStats(workerResults.map(result => result.stats));
        const results = workerResults.flatMap(result => result.entries).sort((a, b) => a.name.localeCompare(b.name));
        const zipStartTime = performance.now();
        const blob = writeZip(results);
        const zipMs = performance.now() - zipStartTime;
        const totalMs = performance.now() - startTime;
        const remapMs = timings.loadMappingsMs + timings.openJarMs + timings.readMs + timings.remapMs + timings.crcMs + timings.compressMs;
        console.log(`Remapped ${results.length} classes for ${version}: indexing=${formatMs(remapIndexMs)} remapping=${formatMs(remapMs)} total=${formatMs(totalMs)}`);
        console.log(
            `[remap:${version}] workers=${threads} classes=${timings.classes} ` +
            `classMapLoad=${formatMs(classMapLoadMs)} remapIndex=${formatMs(remapIndexMs)} loadMappings=${formatMs(timings.loadMappingsMs)} openJar=${formatMs(timings.openJarMs)} ` +
            `read=${formatMs(timings.readMs)} remap=${formatMs(timings.remapMs)} crc=${formatMs(timings.crcMs)} ` +
            `compress=${formatMs(timings.compressMs)} zip=${formatMs(zipMs)} ` +
            `stored=${timings.storedClasses} deflated=${timings.compressedClasses} ` +
            `input=${formatBytes(timings.uncompressedBytes)} output=${formatBytes(timings.outputBytes)}`
        );
        onProgress?.(100);
        return blob;
    } finally {
        await cleanupWorkers(workers);
    }
}

async function cleanupWorkers(workers: ReturnType<typeof createWorker>[]): Promise<void> {
    await Promise.allSettled(workers.map(async worker => {
        try {
            await worker.c.dispose();
        } catch (error) {
            console.warn("Failed to dispose remap worker cleanly", error);
        } finally {
            worker.c[Comlink.releaseProxy]();
            worker.w.terminate();
        }
    }));
}

function mergeStats(stats: RemapWorkerStats[]): RemapWorkerStats {
    return stats.reduce<RemapWorkerStats>((total, stat) => ({
        classes: total.classes + stat.classes,
        loadMappingsMs: total.loadMappingsMs + stat.loadMappingsMs,
        openJarMs: total.openJarMs + stat.openJarMs,
        readMs: total.readMs + stat.readMs,
        remapMs: total.remapMs + stat.remapMs,
        crcMs: total.crcMs + stat.crcMs,
        compressMs: total.compressMs + stat.compressMs,
        compressedClasses: total.compressedClasses + stat.compressedClasses,
        storedClasses: total.storedClasses + stat.storedClasses,
        uncompressedBytes: total.uncompressedBytes + stat.uncompressedBytes,
        outputBytes: total.outputBytes + stat.outputBytes,
    }), {
        classes: 0,
        loadMappingsMs: 0,
        openJarMs: 0,
        readMs: 0,
        remapMs: 0,
        crcMs: 0,
        compressMs: 0,
        compressedClasses: 0,
        storedClasses: 0,
        uncompressedBytes: 0,
        outputBytes: 0,
    });
}

function formatMs(ms: number): string {
    return `${Math.round(ms)}ms`;
}

function formatBytes(bytes: number): string {
    if (bytes < 1024 * 1024) {
        return `${Math.round(bytes / 1024)}KiB`;
    }

    return `${(bytes / (1024 * 1024)).toFixed(1)}MiB`;
}

export type { RemapWorkerResult };
