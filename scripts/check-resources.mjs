#!/usr/bin/env node
// Catches the resource errors Gradle's merger reports, without running Gradle.
//
// The merger only rejects a duplicate when two definitions share the same configuration
// qualifier, so values/ + values-en/ + values-fa/ is legal and is how every locale in this app
// is written. What it does reject is values/colors.xml and values/dashboard_colors.xml both
// claiming `surface`, which is a slow feedback loop for something a script answers instantly.
//
// So: duplicates are counted only within one qualifier, and library styles are ignored because
// they are resolved at compile time and are not declared in this repo.
//
// Usage: node scripts/check-resources.mjs

import { readdirSync, readFileSync, statSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';

const RES = 'android/app/src/main/res';

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (p.endsWith('.xml')) out.push(p);
  }
  return out;
}

/** The resource directory name is the configuration qualifier: values, values-en, values-night. */
const qualifierOf = (file) => {
  const parts = file.split('/');
  const index = parts.lastIndexOf('values');
  return index >= 0 ? parts[index] : parts.slice(-2, -1)[0];
};

const files = walk(RES);
const errors = [];

// 1. Two declarations of the same name in the same qualifier. This is what the merger rejects.
const declared = new Map();
for (const file of files) {
  if (!file.includes('/values')) continue;
  const text = readFileSync(file, 'utf8');
  for (const m of text.matchAll(/<(string|string-array|color|style|integer|bool|dimen)\s+name="([\w.]+)"/g)) {
    const key = `${qualifierOf(file)}|${m[1]}/${m[2]}`;
    if (!declared.has(key)) declared.set(key, []);
    declared.get(key).push(file.replace(RES + '/', ''));
  }
}
for (const [key, where] of declared) {
  if (where.length > 1) errors.push(`duplicate ${key.split('|')[1]}: ${where.join(' and ')}`);
}

// Every name declared anywhere, so a reference can be checked without caring which locale has it.
const names = new Set([...declared.keys()].map((k) => k.split('|')[1]));
const hasName = (kind, name) => names.has(`${kind}/${name}`);

// 2. References to declarations that do not exist. Library styles are excluded: Widget.Material3.*
//    and Theme.Material3.* are resolved from the dependency, not from this tree.
const isLibraryStyle = (name) => /^(Widget|Theme|TextAppearance)\.(Material3|MaterialComponents|AppCompat)\./.test(name);

for (const file of files) {
  const rel = file.replace('android/app/src/', '');
  const text = readFileSync(file, 'utf8');
  for (const m of text.matchAll(/@(string|string-array|color|dimen|integer|style|drawable|layout|array)\/([\w.]+)/g)) {
    const kind = m[1];
    const name = m[2];
    if (kind === 'style') {
      if (isLibraryStyle(name)) continue;
      if (!hasName('style', name)) errors.push(`${rel}: @style/${name} undefined`);
      continue;
    }
    if (kind === 'drawable' || kind === 'layout') {
      if (!existsSync(join(RES, kind, `${name}.xml`)) && !existsSync(join(RES, kind, `${name}.png`)))
        errors.push(`${rel}: @${kind}/${name} has no file`);
      continue;
    }
    if (kind === 'array') {
      if (!hasName('string-array', name)) errors.push(`${rel}: @array/${name} undefined`);
      continue;
    }
    if (!hasName(kind, name)) errors.push(`${rel}: @${kind}/${name} undefined`);
  }
}

if (errors.length) {
  console.error(`resource check: ${errors.length} problem(s)`);
  for (const e of errors) console.error('  ' + e);
  process.exit(1);
}
console.log(`resource check: ${declared.size} declarations across ${new Set([...declared.keys()].map(k => k.split('|')[0])).size} qualifiers, no duplicates, all references resolve`);