import fs from 'fs';
import path from 'path';
import { readBytes } from '@katana-project/zip';

const OUTPUT_DIR = './yarn';

const response = await fetch('https://repo.codemc.io/repository/relativitymc/org/relativitymc/modern-yarn/maven-metadata.xml');
if (!response.ok) throw new Error(`Bad status from Maven: ${response.status}`);
const xml = await response.text();

const allVersions: [string, number][] = Array.from(xml.matchAll(/<version>([^<]+)\+build\.(\d+)<\/version>/g), ([, version, build]) => [version, parseInt(build)]);
const newestVersions: Map<string, number> = allVersions.reduce((versions, [version, build]) => {
	if (!versions.has(version) || versions.get(version) < build) {
		versions.set(version, build);
	}

	return versions;
}, new Map());
console.debug(newestVersions);

if (!fs.existsSync(OUTPUT_DIR)) {
	fs.mkdirSync(OUTPUT_DIR, { recursive: true });
}

for (const [version, build] of newestVersions.entries()) {
	const output = path.join(OUTPUT_DIR, `${version}.tiny`);
	if (fs.existsSync(output)) {
		console.debug(`${output} already exists for ${version}`);
		continue;
	}

	const response = await fetch(`https://repo.codemc.io/repository/relativitymc/org/relativitymc/modern-yarn/${version}+build.${build}/modern-yarn-${version}+build.${build}-mergedv2.jar`);
	if (!response.ok) throw new Error(`Bad status for build ${build} of ${version}: ${response.status}`);
	/*if (!response.body) throw new Error();
	await response.body.pipeTo(Writable.toWeb(fs.createWriteStream(jar)));*/

	const jar = await readBytes(await response.bytes(), {
		naive: true
	});

	const mappings = jar.entries.find(({ name }) => name === 'mappings/mappings.tiny');
	if (!mappings) {
		throw new Error(`No mappings found inside build ${build} of ${version}: Only found ${jar.entries.map(({ name }) => name).join(', ')}`);
	}

	fs.writeFileSync(output, await mappings.bytes());
}
