'use strict';
// Поддельный Firestore для тестов: doc get/set(merge)/create, collection.get, batch, runTransaction.
function createFakeDb() {
  const store = new Map();
  const clone = (v) => (v === undefined ? undefined : JSON.parse(JSON.stringify(v)));
  const snap = (path) => ({ exists: store.has(path), id: path.split('/').pop(), data: () => clone(store.get(path)) });
  const write = (path, obj, opts) => {
    if (opts && opts.merge) store.set(path, { ...(store.get(path) || {}), ...clone(obj) });
    else store.set(path, clone(obj));
  };
  const create = (path, obj) => {
    if (store.has(path)) throw new Error('ALREADY_EXISTS ' + path);
    store.set(path, clone(obj));
  };
  const db = {
    _store: store,
    collection(name) {
      return {
        doc(id) {
          const path = `${name}/${id}`;
          return {
            path,
            async get() { return snap(path); },
            async set(obj, opts) { write(path, obj, opts); },
            async create(obj) { create(path, obj); }
          };
        },
        async get() {
          const docs = [];
          for (const key of store.keys()) if (key.startsWith(name + '/')) docs.push(snap(key));
          return { docs, size: docs.length };
        }
      };
    },
    batch() {
      const ops = [];
      return {
        create(ref, obj) { ops.push(['create', ref.path, obj]); },
        set(ref, obj, opts) { ops.push(['set', ref.path, obj, opts]); },
        async commit() { for (const [t, p, o, op] of ops) (t === 'create' ? create(p, o) : write(p, o, op)); }
      };
    },
    async runTransaction(fn) {
      const writes = [];
      const tx = {
        async get(ref) { return snap(ref.path); },
        set(ref, obj, opts) { writes.push([ref.path, obj, opts]); }
      };
      const result = await fn(tx);
      for (const [p, o, op] of writes) write(p, o, op);
      return result;
    }
  };
  return db;
}
module.exports = { createFakeDb };
