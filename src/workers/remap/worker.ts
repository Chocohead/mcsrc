import * as Comlink from "comlink";
import { load } from "../../../java/build/generated/teavm/wasm-gc/java.wasm-runtime.js";
import indexerWasm from "../../../java/build/generated/teavm/wasm-gc/java.wasm?url";
import { openJar, type Jar } from "../../utils/Jar";
import type { ClassFilePath, ClassName } from "../../utils/Names";
import { crc32 } from "./crc32";
import type { ZipEntryData } from "./zip";

const COMPRESS_REMAPPED_CLASSES = false;

export interface IndexedClassMember {
    readonly method: boolean;
    readonly name: String;
    readonly desc: String;
    readonly accessFlags: number;
}

export interface IndexedClassInstance {
    readonly className: ClassName[];
    readonly superName: ClassName;
    readonly interfaces: ClassName[];
    readonly accessFlags: number;
    readonly members: IndexedClassMember[];
}

export interface ClassMember {
    readonly owner: ClassInstance;
    readonly method: boolean;
    readonly name: string;
    readonly desc: string;
    readonly accessFlags: number;
    newName: string;
    readonly args: Map<number, string>;
    readonly vars: Map<number, string>;
}

export interface ClassInstance {
    readonly className: ClassName[];
    readonly superName: ClassName[];
    readonly interfaces: ClassName[];
    readonly accessFlags: number;
    readonly parents: Set<ClassInstance>;
    readonly children: Set<ClassInstance>;
    readonly members: Map<string, IndexedClassMember>;
    newName: string;
}

export interface RemapClassJob {
    sourcePath: ClassFilePath;
    targetPath: ClassFilePath;
}

export interface RemapWorkerResult extends ZipEntryData {
}

export interface RemapWorkerStats {
    classes: number;
    loadMappingsMs: number;
    openJarMs: number;
    readMs: number;
    remapMs: number;
    crcMs: number;
    compressMs: number;
    compressedClasses: number;
    storedClasses: number;
    uncompressedBytes: number;
    outputBytes: number;
}

export interface RemapWorkerBatchResult {
    entries: RemapWorkerResult[];
    stats: RemapWorkerStats;
}

export class RemapWorker {
    #remapper: Remapper | null = null;
    #jar: Jar | null = null;

    async getRemapper(): Promise<Remapper> {
        if (!this.#remapper) {
            try {
                const teavm = await load(indexerWasm);
                this.#remapper = teavm.exports as Remapper;
            } catch (e) {
                console.warn("Failed to load WASM module (non-compliant browser?), falling back to JS implementation", e);
                this.#remapper = await import("../../../java/build/generated/teavm/js/java.js") as unknown as Remapper;
            }
        }

        return this.#remapper;
    }

    async setJar(name: string, blob: Blob) {
        this.#jar = await openJar(name, blob);
    }

    dispose(): void {
        this.#jar = null;
        this.#remapper?.clearRemapperState2();
        this.#remapper = null;
    }

    async buildRemapIndex(
        sourcePaths: ClassFilePath[],
    ): Promise<IndexedClassInstance[]> {
        if (!this.#jar) {
            throw new Error("Jar not set in worker");
        }
        const jar = this.#jar; // Capture for closure
        const remapper = await this.getRemapper();

        return Promise.all(sourcePaths.map(async (sourcePath) => {
            const entry = jar.entries[sourcePath];

            if (!entry) {
                throw new Error(`Class entry not found: ${sourcePath}`);
            }

            return remapper.index2(toArrayBuffer(await entry.bytes()));
        }));
    }

    async propagateMappings(
        classes: IndexedClassInstance[],
        mappingsBlob: Blob,
    ): Promise<Map<ClassName, ClassInstance>> {
        const remapper = await this.getRemapper();
        const out = remapper.loadMappings2(classes, await mappingsBlob.arrayBuffer());
        await this.loadMappings(out);
        return out;
    }

    async loadMappings(
        mappings: Map<ClassName, ClassInstance>,
    ) {
        const remapper = await this.getRemapper();
        return remapper.receiveClasses(mappings);
    }

    async remapClasses(
        jobs: RemapClassJob[],
    ): Promise<RemapWorkerBatchResult> {
        if (!this.#jar) {
            throw new Error("Jar not set in worker");
        }
        const jar = this.#jar; // Capture for closure
        const remapper = await this.getRemapper();
        const stats: RemapWorkerStats = {
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
        };

        const results: RemapWorkerResult[] = await Promise.all(jobs.map(async (job) => {
            const entry = jar.entries[job.sourcePath];

            if (!entry) {
                throw new Error(`Class entry not found: ${job}`);
            }

            let time = performance.now();
            const classBytes = await entry.bytes();
            stats.readMs += performance.now() - time;

            time = performance.now();
            const remappedBytes = toUint8Array(remapper.remapEntry2(toArrayBuffer(classBytes)));
            stats.remapMs += performance.now() - time;

            time = performance.now();
            const classCrc32 = crc32(remappedBytes);
            stats.crcMs += performance.now() - time;

            time = performance.now();
            const outputBytes = await encodeClass(remappedBytes);
            stats.compressMs += performance.now() - time;

            const out = {
                name: job.targetPath,
                bytes: outputBytes.bytes,
                crc32: classCrc32,
                uncompressedSize: remappedBytes.length,
                compressionMethod: outputBytes.compressionMethod,
            };
            stats.classes++;
            stats.uncompressedBytes += remappedBytes.length;
            stats.outputBytes += outputBytes.bytes.length;
            if (outputBytes.compressionMethod === 8) {
                stats.compressedClasses++;
            } else {
                stats.storedClasses++;
            }
            return out;
        }));

        return { entries: results, stats };
    }
}

async function encodeClass(bytes: Uint8Array<ArrayBuffer>): Promise<{ bytes: Uint8Array<ArrayBuffer>, compressionMethod: 0 | 8; }> {
    if (!COMPRESS_REMAPPED_CLASSES || typeof CompressionStream !== "function") {
        return { bytes, compressionMethod: 0 };
    }

    try {
        const stream = new Blob([bytes]).stream().pipeThrough(new CompressionStream("deflate-raw"));
        const blob = await new Response(stream).blob();
        return {
            bytes: new Uint8Array(await blob.arrayBuffer()),
            compressionMethod: 8,
        };
    } catch (error) {
        console.warn("Failed to deflate remapped class, storing uncompressed", error);
        return { bytes, compressionMethod: 0 };
    }
}

function toUint8Array(bytes: Int8Array): Uint8Array<ArrayBuffer> {
    const copy = new Uint8Array(bytes.byteLength);
    copy.set(new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength));
    return copy;
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
    const copy = new Uint8Array(bytes.byteLength);
    copy.set(bytes);
    return copy.buffer;
}

interface Remapper {
    index2(data: ArrayBufferLike): IndexedClassInstance;
    loadMappings2(indexedClasses: IndexedClassInstance[], mappings: ArrayBufferLike): Map<ClassName, ClassInstance>;
    receiveClasses(classes: Map<ClassName, ClassInstance>): void;
    remapEntry2(classData: ArrayBufferLike): Int8Array;
    clearRemapperState2(): void;
}

Comlink.expose(new RemapWorker());
