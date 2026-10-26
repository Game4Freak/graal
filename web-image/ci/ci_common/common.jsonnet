local utils = import '../../../ci/ci_common/common-utils.libsonnet';
local common = import '../../../ci/ci_common/common.jsonnet';

local node_map = {
  '22': 'v22.9.0',
};

{
  process_gate_predicate(build, suites, extra_includes=[], extra_excludes=[])::
    if std.member(build.targets, 'gate') || std.member(build.targets, 'tier1') || std.member(build.targets, 'tier2') || std.member(build.targets, 'tier3') then
      build {
        guard+: {
          includes+: [suite + '/**' for suite in suites] + extra_includes + utils.top_level_ci,
          excludes+: [suite + '/docs/**' for suite in suites] + extra_excludes,
        },
        setup+: [
          // Ensures all repos are cloned
          ['mx', 'sversion'],
          ['apply-predicates', '--delete-excluded', '--process-hidden', '--pattern-root', '..']
          + ['--exclude=' + e for e in utils.std_get(self.guard, 'excludes', [])]
          + ['--include=' + e for e in utils.std_get(self.guard, 'includes', [])],
        ],
      }
    else
      build,

  /*
   * Creates command to prepend the given path to $PATH on the given platform
   */
  prepend_path(path, os):
    local is_windows = os == 'windows';
    local canonical_path = if is_windows then std.strReplace(path, '/', '\\') else path;
    local separator = if is_windows then ';' else ':';
    ['set-export', 'PATH', canonical_path + separator + '$PATH'],

  node(version_tag): {
    local is_windows = self.os == 'windows',
    downloads+: {
      /*
       * On windows, the node executable is at the root of the download
       */
      NODE: { name: 'node', version: node_map[version_tag], platformspecific: true },
    },
    environment+: {
      NODE_EXE: if is_windows then '$NODE\\node.exe' else '$NODE/bin/node',
    },
    setup+: [
      $.prepend_path(if is_windows then '$NODE' else '$NODE/bin', self.os),
      ['node', '--version'],
    ],
  },
  node22: self.node('22'),

  wabt: {
    downloads+: {
      WABT_DIR: { name: 'wabt', version: '1.0.32', platformspecific: true },
    },
    setup+: [
      $.prepend_path('$WABT_DIR/bin', self.os),
      ['wat2wasm', '--version'],
    ],
  },

  binaryen: {
    downloads+: {
      BINARYEN_DIR: { name: 'binaryen', version: 'version_119', platformspecific: true },
    },
    setup+: [
      $.prepend_path('$BINARYEN_DIR/bin', self.os),
      ['wasm-as', '--version'],
    ],
  },

  // wasm-tools turns the core module of the WASI target into a component.
  // The download artifact must provide the release archive of https://github.com/bytecodealliance/wasm-tools.
  wasm_tools: {
    downloads+: {
      WASM_TOOLS_DIR: { name: 'wasm-tools', version: '1.262.0', platformspecific: true },
    },
    setup+: [
      $.prepend_path('$WASM_TOOLS_DIR', self.os),
      ['wasm-tools', '--version'],
    ],
  },

  // wasmtime runs the WASI components produced by the WASI target in tests.
  // The download artifact must provide the release archive of https://github.com/bytecodealliance/wasmtime (46 or later).
  wasmtime: {
    downloads+: {
      WASMTIME_DIR: { name: 'wasmtime', version: '50.0.0', platformspecific: true },
    },
    setup+: [
      $.prepend_path('$WASMTIME_DIR', self.os),
      ['wasmtime', '--version'],
    ],
    environment+: {
      WASMTIME_EXE: 'wasmtime',
    },
  },

  maven: {
    packages+: {
      maven: '==3.9.10',
    },
  },

  guard_suites: ['<graal>/web-image', '<graal>/substratevm', '<graal>/compiler', '<graal>/sdk', '<graal>/truffle', '<graal>/wasm', '<graal>/espresso-shared'],
  // Ensure .git folder is not deleted by apply-predicates
  extra_includes: ['<graal>/.git/**', '<graal>/common.json'],

  catch_test_failures: {
    catch_files+: [
      'Dumping test fail to (?P<filename>.+\\.zip)',
    ],
  },

  gate_prettier: self.node22 {
    setup+: [
      ['npm', 'install', 'prettier'],
    ],
    environment+: {
      PRETTIER_EXE: 'npx prettier',
    },
  },

  jdt: common.deps.jdt,
  spotbugs: common.deps.spotbugs,
  svm: common.deps.svm,
}
