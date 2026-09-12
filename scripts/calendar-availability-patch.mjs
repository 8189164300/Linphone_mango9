// Emit an apply_patch for the pinned Exyte copies without changing their behavior.
// Run only against a fresh upstream export (see Vendor/README.md).
import fs from 'node:fs';
import path from 'node:path';
const root = path.resolve(import.meta.dirname, '..');
const declaration = /^(?:(?:public|internal|private|fileprivate|open|final|indirect|nonisolated)\s+|@(?:MainActor|preconcurrency)\s+)*(?:struct|class|enum|protocol|extension|func|let|var|typealias|actor)\b/;
let patch = '*** Begin Patch\n';
function walk(directory) {
  for (const entry of fs.readdirSync(directory, {withFileTypes:true})) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) walk(file);
    else if (file.endsWith('.swift')) {
      const source = fs.readFileSync(file, 'utf8');
      const lines = source.split('\n');
      const additions = lines.filter(line => declaration.test(line));
      if (!additions.length) continue;
      if (source.includes('@available(iOS 18.0, *)')) throw new Error('Already annotated: '+file);
      patch += '*** Update File: '+file+'\n';
      for (const line of additions) {
        // Pure retroactive conformances have no newer API and must be unconditionally available.
        if (/^extension (NSPredicate|EKEventStore|Color|Int):/.test(line)) continue;
        patch += '@@\n+@available(iOS 18.0, *)\n '+line+'\n';
      }
    }
  }
}
walk(path.join(root, 'Vendor/CalendarView/Sources'));
walk(path.join(root, 'Vendor/AnchoredPopup/Sources'));
patch += '*** End Patch\n';
process.stdout.write(patch);
