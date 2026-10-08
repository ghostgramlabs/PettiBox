// An in-memory Google Drive that answers the REST calls drive.js makes
// (files.list with the query forms we use, create, multipart/media upload,
// PATCH with addParents/removeParents, delete, alt=media), so drive.js runs
// unchanged in tests. Install with installFakeDrive(); it replaces fetch and
// chrome.identity.

let seq = 0;
let clock = 1;

export function installFakeDrive() {
  const files = new Map(); // id -> { id, name, mimeType, parents, createdTime, content: Uint8Array }
  const make = (meta, content = new Uint8Array()) => {
    const id = 'f' + (++seq);
    files.set(id, { id, name: meta.name, mimeType: meta.mimeType ?? 'application/octet-stream', parents: meta.parents ?? ['root'], createdTime: clock++, content });
    return id;
  };

  const matches = (f, q) => q.split(' and ').every((term) => {
    let m;
    if ((m = /^name = '(.*)'$/.exec(term))) return f.name === m[1];
    if ((m = /^name != '(.*)'$/.exec(term))) return f.name !== m[1];
    if ((m = /^name contains '(.*)'$/.exec(term))) return f.name.includes(m[1]);
    if ((m = /^mimeType = '(.*)'$/.exec(term))) return f.mimeType === m[1];
    if ((m = /^'(.*)' in parents$/.exec(term))) return f.parents.includes(m[1]);
    if (term === 'trashed = false') return true;
    throw new Error('fake Drive: unsupported query term: ' + term);
  });

  const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

  async function bodyBytes(init) {
    const b = init.body;
    if (b instanceof Blob) return new Uint8Array(await b.arrayBuffer());
    if (b instanceof Uint8Array) return b;
    return new TextEncoder().encode(String(b ?? ''));
  }

  globalThis.fetch = async (input, init = {}) => {
    const url = new URL(String(input));
    const method = init.method ?? 'GET';
    const path = url.pathname;
    let m;
    if (path === '/drive/v3/files' && method === 'GET') {
      const q = url.searchParams.get('q') ?? '';
      const list = [...files.values()].filter((f) => matches(f, q)).sort((a, b) => a.createdTime - b.createdTime);
      return json({ files: list.map((f) => ({ id: f.id, name: f.name, parents: f.parents, mimeType: f.mimeType, size: String(f.content.length), md5Checksum: '', modifiedTime: new Date(f.createdTime).toISOString() })) });
    }
    if (path === '/drive/v3/files' && method === 'POST') {
      return json({ id: make(JSON.parse(init.body)) });
    }
    if (path === '/upload/drive/v3/files' && method === 'POST') {
      // multipart/related: JSON metadata part, then the content part.
      const bytes = await bodyBytes(init);
      const text = new TextDecoder('latin1').decode(bytes);
      const boundary = /boundary=(.+)$/.exec(init.headers['Content-Type'])[1];
      const parts = text.split('--' + boundary);
      const meta = JSON.parse(parts[1].split('\r\n\r\n')[1].trim());
      const contentStart = text.indexOf('\r\n\r\n', text.indexOf(parts[2])) + 4;
      const contentEnd = text.lastIndexOf('\r\n--' + boundary + '--');
      return json({ id: make(meta, bytes.slice(contentStart, contentEnd)) });
    }
    if ((m = /^\/upload\/drive\/v3\/files\/([^/]+)$/.exec(path)) && method === 'PATCH') {
      files.get(m[1]).content = await bodyBytes(init);
      return json({ id: m[1] });
    }
    if ((m = /^\/drive\/v3\/files\/([^/]+)$/.exec(path))) {
      const f = files.get(m[1]);
      if (!f) return json({ error: 'not found' }, 404);
      if (method === 'DELETE') {
        const drop = (id) => { for (const c of [...files.values()]) if (c.parents.includes(id)) drop(c.id); files.delete(id); };
        drop(f.id);
        return new Response(null, { status: 204 });
      }
      if (method === 'PATCH') {
        const add = url.searchParams.get('addParents');
        const remove = (url.searchParams.get('removeParents') ?? '').split(',').filter(Boolean);
        f.parents = [...f.parents.filter((p) => !remove.includes(p)), ...(add ? [add] : [])];
        const meta = init.body ? JSON.parse(init.body) : {};
        if (meta.name) f.name = meta.name;
        return json({ id: f.id });
      }
      if (url.searchParams.get('alt') === 'media') return new Response(f.content, { status: 200 });
    }
    if (path === '/drive/v3/about') return json({ user: { emailAddress: 'me@example.com' } });
    throw new Error(`fake Drive: unhandled ${method} ${url}`);
  };

  globalThis.chrome = {
    identity: {
      getAuthToken: async () => ({ token: 'fake-token' }),
      removeCachedAuthToken: async () => {},
    },
  };

  const folder = (name, parent = 'root') => make({ name, mimeType: 'application/vnd.google-apps.folder', parents: [parent] });
  /** Folders by path, e.g. tree() -> ['PettiBox', 'PettiBox/Sync', 'PettiBox/Sync/d-…']. */
  const tree = () => {
    const pathOf = (f) => (f.parents[0] === 'root' ? '' : pathOf(files.get(f.parents[0])) + '/') + f.name;
    return [...files.values()].map(pathOf).sort();
  };
  return { files, folder, make, tree };
}
