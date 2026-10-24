import { realpath } from 'node:fs/promises';
import { dirname, isAbsolute, relative, resolve } from 'node:path';

/** Resolve missing descendants through the nearest real ancestor, refusing link escapes. */
export async function workspacePath(root: string, input: string): Promise<string> {
  if (!input || input.includes('\0')) throw new Error('Invalid workspace path');
  root=await realpath(root);
  const requested=resolve(root,input);
  const within=(path: string) => { const rel=relative(root,path);return rel!== '..' && !rel.startsWith('../') && !rel.startsWith('..\\') && !isAbsolute(rel); };
  if (!within(requested)) throw new Error('Outside workspace');
  let cursor=requested;
  while (true) {
    try {
      const canonical=await realpath(cursor);
      if (!within(canonical)) throw new Error('Symlink outside workspace');
      const result=resolve(canonical,relative(cursor,requested));
      if (!within(result)) throw new Error('Outside workspace');
      return result;
    } catch(error) {
      if ((error as NodeJS.ErrnoException).code!=='ENOENT') throw error;
      const parent=dirname(cursor);if(parent===cursor)throw error;cursor=parent;
    }
  }
}
