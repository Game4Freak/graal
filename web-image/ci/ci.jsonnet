local ci_common = import '../../ci/ci_common/common.jsonnet';
local common = import 'ci_common/common.jsonnet';
local utils = (import '../../ci/ci_common/common-utils.libsonnet');
local r = import 'ci_common/wi-run-spec.jsonnet';

local mxgate(tags) = r.mxgate(tags, suite='web-image');

local platforms = r.platforms;
local t = r.t;

local daily = r.daily;
local tier4 = r.tier4;

{
  // THE TASK CONFIGURATION
  task_dict:: {
    // TODO GR-67831 Split into separate style and fullbuild jobs
    'style-fullbuild': mxgate('style,fullbuild,webimagehelp,webimageoptions') + t('30:00') + r.jdt + r.spotbugs + r.prettier + r.notify.base + platforms({
      'linux:amd64:jdk-latest': daily,
    }),
    'unittest': mxgate('webimagebuild,webimageunittest') + t('30:00') + r.notify.base + r.node22 + platforms({
      'linux:amd64:jdk-latest': tier4,
    }),
    // Runs the spec tests as WASI components under wasmtime (WASI target of the Wasm backend)
    'wasitest': mxgate('webimagebuild,webimagewasitest') + t('45:00') + r.notify.wasm + r.node22 + r.binaryen + r.wasm_tools + r.wasmtime + platforms({
      'linux:amd64:jdk-latest': daily,
    }),
  },
  processed_tasks:: r.process(self.task_dict),
  builds: [
    common.process_gate_predicate(b, common.guard_suites, common.extra_includes)
    for b in utils.add_defined_in(self.processed_tasks.list, std.thisFile)
  ],
}
