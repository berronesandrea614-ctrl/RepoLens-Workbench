#!/usr/bin/env node
/*
 * RepoLens multi-language code parser sidecar.
 * Phase 1: TypeScript / JavaScript.  Phase 2: Python, Go.
 *
 * Contract (stable — RepoLens Java side depends on this):
 *   Invocation:  node index.js <repoDir>
 *   Stdin:       JSON array of repo-relative file paths to parse, e.g. ["src/a.ts","m.go"].
 *                If stdin is empty, the sidecar walks <repoDir> for supported files.
 *   Stdout:      one JSON object:
 *     {
 *       "ok": true,
 *       "results": [
 *         {
 *           "path": "src/service.ts",
 *           "language": "typescript|javascript|python|go",
 *           "symbols": [
 *             { "symbolType": "CLASS|INTERFACE|FUNCTION|METHOD",
 *               "name": "UserService#addUser",   // qualified, unique within file
 *               "className": "UserService|null",
 *               "methodName": "addUser|null",
 *               "startLine": 10, "endLine": 13,
 *               "signature": "addUser()" }
 *           ],
 *           "dependencies": [
 *             { "sourceName": "UserService#addUser", // matches a symbol.name in this file
 *               "targetName": "validate",            // callee name (resolved by name downstream)
 *               "relationType": "CALL" }
 *           ]
 *         }
 *       ]
 *     }
 *   On fatal error: { "ok": false, "error": "..." } and exit code 1.
 *
 * Design: out-of-process (like the ripgrep shell-out), so a parser crash never takes
 * down the JVM, and adding a language == adding a tree-sitter grammar + a walker.
 */

"use strict";

const fs = require("fs");
const path = require("path");

// ── grammar registry (extension → { grammar, logical language, walker family }) ──
function loadGrammars() {
  const TS = require("tree-sitter-typescript");
  const JS = require("tree-sitter-javascript");
  const PY = require("tree-sitter-python");
  const GO = require("tree-sitter-go");
  const RS = require("tree-sitter-rust");
  const CS = require("tree-sitter-c-sharp");
  const RB = require("tree-sitter-ruby");
  return {
    ".ts": { lang: TS.typescript, language: "typescript", family: "tsjs" },
    ".mts": { lang: TS.typescript, language: "typescript", family: "tsjs" },
    ".cts": { lang: TS.typescript, language: "typescript", family: "tsjs" },
    ".tsx": { lang: TS.tsx, language: "typescript", family: "tsjs" },
    ".js": { lang: JS, language: "javascript", family: "tsjs" },
    ".mjs": { lang: JS, language: "javascript", family: "tsjs" },
    ".cjs": { lang: JS, language: "javascript", family: "tsjs" },
    ".jsx": { lang: JS, language: "javascript", family: "tsjs" },
    ".py": { lang: PY, language: "python", family: "python" },
    ".pyi": { lang: PY, language: "python", family: "python" },
    ".go": { lang: GO, language: "go", family: "go" },
    ".rs": { lang: RS, language: "rust", family: "rust" },
    ".cs": { lang: CS, language: "csharp", family: "csharp" },
    ".rb": { lang: RB, language: "ruby", family: "ruby" },
  };
}

const SUPPORTED_EXTS = [
  ".ts", ".mts", ".cts", ".tsx", ".js", ".mjs", ".cjs", ".jsx",
  ".py", ".pyi", ".go", ".rs", ".cs", ".rb",
];

function nameOf(node) {
  if (!node) return null;
  const n = node.childForFieldName("name");
  return n ? n.text : null;
}

function pushSymbol(out, symbolType, name, className, methodName, node, signature) {
  out.symbols.push({
    symbolType, name, className, methodName,
    startLine: node.startPosition.row + 1, endLine: node.endPosition.row + 1,
    signature: signature || name,
  });
}

// ── TS / JS ──────────────────────────────────────────────────────────────────
function tsjsCallee(callNode) {
  const fn = callNode.childForFieldName("function");
  if (!fn) return null;
  if (fn.type === "identifier") return fn.text;
  if (fn.type === "member_expression") {
    const prop = fn.childForFieldName("property");
    return prop ? prop.text : null;
  }
  return null;
}

function walkTsJs(node, ctx, out) {
  const t = node.type;
  if (t === "class_declaration" || t === "abstract_class_declaration") {
    const cn = nameOf(node);
    if (cn) pushSymbol(out, "CLASS", cn, cn, null, node, cn);
    for (const c of node.namedChildren) walkTsJs(c, { ...ctx, currentClass: cn }, out);
    return;
  }
  if (t === "interface_declaration") {
    const cn = nameOf(node);
    if (cn) pushSymbol(out, "INTERFACE", cn, cn, null, node, cn);
    return;
  }
  if (t === "function_declaration" || t === "generator_function_declaration") {
    const fn = nameOf(node);
    if (fn) {
      pushSymbol(out, "FUNCTION", fn, null, fn, node, fn + "()");
      const body = node.childForFieldName("body");
      if (body) for (const c of body.namedChildren) walkTsJs(c, { ...ctx, currentClass: null, currentFn: fn }, out);
    }
    return;
  }
  if (t === "method_definition") {
    const fn = nameOf(node);
    if (fn) {
      const cls = ctx.currentClass || null;
      const qn = cls ? cls + "#" + fn : fn;
      pushSymbol(out, "METHOD", qn, cls, fn, node, (cls ? cls + "." : "") + fn + "()");
      const body = node.childForFieldName("body");
      if (body) for (const c of body.namedChildren) walkTsJs(c, { ...ctx, currentFn: qn }, out);
    }
    return;
  }
  if (t === "variable_declarator") {
    const value = node.childForFieldName("value");
    const nm = nameOf(node);
    if (nm && value && (value.type === "arrow_function" || value.type === "function" || value.type === "function_expression")) {
      const qn = ctx.currentClass ? ctx.currentClass + "#" + nm : nm;
      pushSymbol(out, "FUNCTION", qn, ctx.currentClass || null, nm, node, nm + "()");
      const body = value.childForFieldName("body");
      if (body) for (const c of body.namedChildren) walkTsJs(c, { ...ctx, currentFn: qn }, out);
      return;
    }
  }
  if (t === "call_expression") {
    const callee = tsjsCallee(node);
    if (callee && ctx.currentFn) out.dependencies.push({ sourceName: ctx.currentFn, targetName: callee, relationType: "CALL" });
  }
  for (const c of node.namedChildren) walkTsJs(c, ctx, out);
}

// ── Python ───────────────────────────────────────────────────────────────────
function pyCallee(callNode) {
  const fn = callNode.childForFieldName("function");
  if (!fn) return null;
  if (fn.type === "identifier") return fn.text;
  if (fn.type === "attribute") {
    const attr = fn.childForFieldName("attribute");
    return attr ? attr.text : null;
  }
  return null;
}

function walkPython(node, ctx, out) {
  const t = node.type;
  if (t === "class_definition") {
    const cn = nameOf(node);
    if (cn) pushSymbol(out, "CLASS", cn, cn, null, node, cn);
    const body = node.childForFieldName("body");
    if (body) for (const c of body.namedChildren) walkPython(c, { ...ctx, currentClass: cn }, out);
    return;
  }
  if (t === "function_definition") {
    const fn = nameOf(node);
    if (fn) {
      const cls = ctx.currentClass || null;
      const qn = cls ? cls + "#" + fn : fn;
      pushSymbol(out, cls ? "METHOD" : "FUNCTION", qn, cls, fn, node, (cls ? cls + "." : "") + fn + "()");
      const body = node.childForFieldName("body");
      if (body) for (const c of body.namedChildren) walkPython(c, { ...ctx, currentClass: null, currentFn: qn }, out);
    }
    return;
  }
  if (t === "call") {
    const callee = pyCallee(node);
    if (callee && ctx.currentFn) out.dependencies.push({ sourceName: ctx.currentFn, targetName: callee, relationType: "CALL" });
  }
  for (const c of node.namedChildren) walkPython(c, ctx, out);
}

// ── Go ───────────────────────────────────────────────────────────────────────
function goCallee(callNode) {
  const fn = callNode.childForFieldName("function");
  if (!fn) return null;
  if (fn.type === "identifier") return fn.text;
  if (fn.type === "selector_expression") {
    const field = fn.childForFieldName("field");
    return field ? field.text : null;
  }
  return null;
}

function goReceiverType(node) {
  const recv = node.childForFieldName("receiver");
  if (!recv) return null;
  let found = null;
  (function dig(n) {
    if (found || !n) return;
    if (n.type === "type_identifier") { found = n.text; return; }
    for (const c of n.namedChildren) dig(c);
  })(recv);
  return found;
}

function walkGo(node, ctx, out) {
  const t = node.type;
  if (t === "type_declaration") {
    for (const spec of node.namedChildren) {
      if (spec.type !== "type_spec") continue;
      const nm = spec.childForFieldName("name");
      const ty = spec.childForFieldName("type");
      if (nm) {
        const kind = ty && ty.type === "interface_type" ? "INTERFACE" : "CLASS";
        pushSymbol(out, kind, nm.text, nm.text, null, spec, nm.text);
      }
    }
    return;
  }
  if (t === "function_declaration") {
    const fn = nameOf(node);
    if (fn) {
      pushSymbol(out, "FUNCTION", fn, null, fn, node, fn + "()");
      const body = node.childForFieldName("body");
      if (body) walkGo(body, { ...ctx, currentFn: fn }, out);
    }
    return;
  }
  if (t === "method_declaration") {
    const fn = nameOf(node);
    if (fn) {
      const cls = goReceiverType(node);
      const qn = cls ? cls + "#" + fn : fn;
      pushSymbol(out, "METHOD", qn, cls, fn, node, (cls ? cls + "." : "") + fn + "()");
      const body = node.childForFieldName("body");
      if (body) walkGo(body, { ...ctx, currentFn: qn }, out);
    }
    return;
  }
  if (t === "call_expression") {
    const callee = goCallee(node);
    if (callee && ctx.currentFn) out.dependencies.push({ sourceName: ctx.currentFn, targetName: callee, relationType: "CALL" });
  }
  for (const c of node.namedChildren) walkGo(c, ctx, out);
}

// ── Rust ─────────────────────────────────────────────────────────────────────
function rustCallee(callNode) {
  const fn = callNode.childForFieldName("function");
  if (!fn) return null;
  if (fn.type === "identifier") return fn.text;
  if (fn.type === "field_expression") {
    const field = fn.childForFieldName("field");
    return field ? field.text : null;
  }
  if (fn.type === "scoped_identifier") {
    const name = fn.childForFieldName("name");
    return name ? name.text : null;
  }
  return null;
}

function walkRust(node, ctx, out) {
  const t = node.type;
  if (t === "struct_item" || t === "enum_item" || t === "union_item") {
    const cn = nameOf(node);
    if (cn) pushSymbol(out, "CLASS", cn, cn, null, node, cn);
    return;
  }
  if (t === "trait_item") {
    const cn = nameOf(node);
    if (cn) pushSymbol(out, "INTERFACE", cn, cn, null, node, cn);
    const body = node.childForFieldName("body");
    if (body) for (const c of body.namedChildren) walkRust(c, { ...ctx, currentClass: cn }, out);
    return;
  }
  if (t === "impl_item") {
    const ty = node.childForFieldName("type");
    const cls = ty ? ty.text : null;
    const body = node.childForFieldName("body");
    if (body) for (const c of body.namedChildren) walkRust(c, { ...ctx, currentClass: cls }, out);
    return;
  }
  if (t === "function_item") {
    const fn = nameOf(node);
    if (fn) {
      const cls = ctx.currentClass || null;
      const qn = cls ? cls + "#" + fn : fn;
      pushSymbol(out, cls ? "METHOD" : "FUNCTION", qn, cls, fn, node, (cls ? cls + "." : "") + fn + "()");
      const body = node.childForFieldName("body");
      if (body) walkRust(body, { ...ctx, currentClass: null, currentFn: qn }, out);
    }
    return;
  }
  if (t === "call_expression") {
    const callee = rustCallee(node);
    if (callee && ctx.currentFn) out.dependencies.push({ sourceName: ctx.currentFn, targetName: callee, relationType: "CALL" });
  }
  for (const c of node.namedChildren) walkRust(c, ctx, out);
}

// ── C# ───────────────────────────────────────────────────────────────────────
function csharpCallee(callNode) {
  const fn = callNode.childForFieldName("function");
  if (!fn) return null;
  if (fn.type === "identifier") return fn.text;
  if (fn.type === "member_access_expression") {
    const name = fn.childForFieldName("name");
    return name ? name.text : null;
  }
  return null;
}

function walkCSharp(node, ctx, out) {
  const t = node.type;
  if (t === "class_declaration" || t === "struct_declaration" || t === "record_declaration") {
    const cn = nameOf(node);
    if (cn) pushSymbol(out, "CLASS", cn, cn, null, node, cn);
    const body = node.childForFieldName("body");
    if (body) for (const c of body.namedChildren) walkCSharp(c, { ...ctx, currentClass: cn }, out);
    return;
  }
  if (t === "interface_declaration") {
    const cn = nameOf(node);
    if (cn) pushSymbol(out, "INTERFACE", cn, cn, null, node, cn);
    return;
  }
  if (t === "method_declaration" || t === "constructor_declaration" || t === "local_function_statement") {
    const fn = nameOf(node);
    if (fn) {
      const cls = ctx.currentClass || null;
      const qn = cls ? cls + "#" + fn : fn;
      pushSymbol(out, cls ? "METHOD" : "FUNCTION", qn, cls, fn, node, (cls ? cls + "." : "") + fn + "()");
      const body = node.childForFieldName("body");
      if (body) walkCSharp(body, { ...ctx, currentFn: qn }, out);
    }
    return;
  }
  if (t === "invocation_expression") {
    const callee = csharpCallee(node);
    if (callee && ctx.currentFn) out.dependencies.push({ sourceName: ctx.currentFn, targetName: callee, relationType: "CALL" });
  }
  for (const c of node.namedChildren) walkCSharp(c, ctx, out);
}

// ── Ruby ─────────────────────────────────────────────────────────────────────
function rubyCallee(callNode) {
  const m = callNode.childForFieldName("method");
  return m ? m.text : null;
}

function walkRuby(node, ctx, out) {
  const t = node.type;
  if (t === "class" || t === "module") {
    const nm = node.childForFieldName("name");
    const cn = nm ? nm.text : null;
    if (cn && t === "class") pushSymbol(out, "CLASS", cn, cn, null, node, cn);
    for (const c of node.namedChildren) walkRuby(c, { ...ctx, currentClass: cn || ctx.currentClass }, out);
    return;
  }
  if (t === "method" || t === "singleton_method") {
    const nm = node.childForFieldName("name");
    const fn = nm ? nm.text : null;
    if (fn) {
      const cls = ctx.currentClass || null;
      const qn = cls ? cls + "#" + fn : fn;
      pushSymbol(out, cls ? "METHOD" : "FUNCTION", qn, cls, fn, node, (cls ? cls + "." : "") + fn + "()");
      const body = node.childForFieldName("body");
      if (body) walkRuby(body, { ...ctx, currentClass: null, currentFn: qn }, out);
    }
    return;
  }
  if (t === "call" || t === "method_call") {
    const callee = rubyCallee(node);
    if (callee && ctx.currentFn) out.dependencies.push({ sourceName: ctx.currentFn, targetName: callee, relationType: "CALL" });
  }
  for (const c of node.namedChildren) walkRuby(c, ctx, out);
}

const WALKERS = {
  tsjs: walkTsJs, python: walkPython, go: walkGo,
  rust: walkRust, csharp: walkCSharp, ruby: walkRuby,
};

function parseFile(Parser, grammars, repoDir, relPath) {
  const ext = path.extname(relPath).toLowerCase();
  const g = grammars[ext];
  if (!g) return null;
  const abs = path.join(repoDir, relPath);
  let source;
  try {
    source = fs.readFileSync(abs, "utf8");
  } catch {
    return null;
  }
  const parser = new Parser();
  parser.setLanguage(g.lang);
  const tree = parser.parse(source);
  const out = { symbols: [], dependencies: [] };
  (WALKERS[g.family] || walkTsJs)(tree.rootNode, { currentClass: null, currentFn: null }, out);
  // dedup dependencies (same src→tgt→type)
  const seen = new Set();
  out.dependencies = out.dependencies.filter((d) => {
    const k = d.sourceName + "→" + d.targetName + "→" + d.relationType;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
  return { path: relPath, language: g.language, symbols: out.symbols, dependencies: out.dependencies };
}

function walkDir(repoDir) {
  const found = [];
  const IGNORE = new Set(["node_modules", ".git", "dist", "build", "out", "target", ".next", "coverage", "vendor"]);
  (function rec(dir) {
    let entries;
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const e of entries) {
      if (e.name.startsWith(".") && e.name !== ".") continue;
      const full = path.join(dir, e.name);
      if (e.isDirectory()) {
        if (!IGNORE.has(e.name)) rec(full);
      } else if (SUPPORTED_EXTS.includes(path.extname(e.name).toLowerCase())) {
        found.push(path.relative(repoDir, full));
      }
    }
  })(repoDir);
  return found;
}

function readStdin() {
  try {
    const data = fs.readFileSync(0, "utf8").trim();
    if (!data) return null;
    const arr = JSON.parse(data);
    return Array.isArray(arr) ? arr : null;
  } catch {
    return null;
  }
}

function main() {
  const repoDir = process.argv[2];
  if (!repoDir) {
    process.stdout.write(JSON.stringify({ ok: false, error: "usage: node index.js <repoDir>" }));
    process.exit(1);
  }
  let Parser, grammars;
  try {
    Parser = require("tree-sitter");
    grammars = loadGrammars();
  } catch (e) {
    process.stdout.write(JSON.stringify({ ok: false, error: "grammar load failed: " + (e && e.message) }));
    process.exit(1);
  }
  const relFiles = readStdin() || walkDir(repoDir);
  const results = [];
  for (const rel of relFiles) {
    try {
      const r = parseFile(Parser, grammars, repoDir, rel);
      if (r) results.push(r);
    } catch (e) {
      results.push({ path: rel, language: "unknown", symbols: [], dependencies: [], error: String(e && e.message) });
    }
  }
  process.stdout.write(JSON.stringify({ ok: true, results }));
}

main();
