import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {pathToFileURL,fileURLToPath} from 'node:url';
import {resolve,dirname} from 'node:path';
const host=resolve(dirname(fileURLToPath(import.meta.url)),'../../..');
const require=createRequire(resolve(host,'package.json'));
const manifest=JSON.parse(await readFile(resolve(host,'dist/generated-self-root/self/manifest.json'),'utf8')) as {plugins:Array<{id:string;name:string}>};
let count=0;
for(const plugin of manifest.plugins) {
  const target=plugin.name.startsWith('plugins/') ? resolve(host,'dist',plugin.name) : require.resolve(plugin.name);
  try {await import(pathToFileURL(target).href);count++;}
  catch(error) {throw new Error(`Packed plugin import failed: ${plugin.id}`,{cause:error});}
}
console.log(`PACKED_MODULES_OK ${count} Node ${process.versions.node}`);
