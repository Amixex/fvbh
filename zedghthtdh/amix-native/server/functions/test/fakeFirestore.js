'use strict';
// Минимальный поддельный Firestore для тестов: collection().doc(), get/set (merge), runTransaction.
function createFakeDb() {
  const store = new Map();
  const clone = (v) => (v === undefined ? undefined : JSON.parse(JSON.stringify(v)));
  const snap = (path) => ({
    exists: store.has(path),
    data: () => clone(store.get(path))
  });
  const write = (path, obj, opts) => {
    if (opts && opts.merge) store.set(path, { ...(store.get(path) || {}), ...clone(obj) });
    else store.set(path, clone(obj));
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
            async set(obj, opts) { write(path, obj, opts); }
          };
        }
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
