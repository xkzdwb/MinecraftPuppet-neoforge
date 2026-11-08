// 生成 net.neoforged:minecraft-dependencies:<mcversion> 的 Gradle 元数据到本地仓库。
//
// 为什么需要这个：
//   NeoForge 的 neoform 依赖 net.neoforged:minecraft-dependencies，它只存在于
//   maven.neoforged.net（mojang-meta），该域名在本机不可达，creeperhost 镜像也没有这个构件。
//   这个构件的内容其实就是“MC 运行时要用的第三方库清单”，可以从 Mojang 官方版本清单完整还原。
//
// 关键细节：
//   - 必须保留 classifier（natives，如 linux-x86_64 / osx-aarch_64），
//     否则 Gradle 会去找不带 classifier 的 jar 而 404；
//   - 必须按 rules 过滤 platform 专属库，只保留与本机构建线程无关的通用库；
//   - natives 只在 Maven Central 有，主库在 Mojang 库。
//
// 用法：
//   node tools/gen-mc-deps.mjs 26.2 <输出目录>

import fs from 'node:fs';
import path from 'node:path';

const mcVersion = process.argv[2] || '26.2';
const outRoot = path.resolve(process.argv[3] || '.build-mcdeps');

const manifest = await (await fetch('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json')).json();
const entry = manifest.versions.find((v) => v.id === mcVersion);
if (!entry) {
  console.error(`找不到 MC 版本 ${mcVersion}`);
  process.exit(1);
}
const versionJson = await (await fetch(entry.url)).json();

const currentOs = process.platform === 'win32' ? 'windows' : process.platform === 'darwin' ? 'osx' : 'linux';
const currentArch = process.arch === 'x64' ? 'x86_64' : process.arch === 'arm64' ? 'aarch_64' : process.arch;

/** Mojang 的 rules：先匹配的规则决定 allow/deny，没有匹配则默认 allow。 */
function allowedByRules(rules) {
  if (!rules || rules.length === 0) return true;
  for (const rule of rules) {
    let matches = true;
    if (rule.os) {
      if (rule.os.name && rule.os.name !== currentOs) matches = false;
      if (rule.os.arch && rule.os.arch !== currentArch) matches = false;
      if (rule.os.version) {
        // 版本正则只在 Windows 上生效，这里保守放行
      }
    }
    if (matches) return rule.action === 'allow';
  }
  return false;
}

const deps = [];
const seen = new Set();
for (const lib of versionJson.libraries) {
  const artifact = lib.downloads?.artifact;
  if (!artifact) continue;
  if (!allowedByRules(lib.rules)) continue;
  const parts = lib.name.split(':');
  const [group, name, version] = parts;
  const classifier = parts[3];
  const key = `${group}:${name}:${classifier ?? ''}`;
  if (seen.has(key)) continue;
  seen.add(key);
  deps.push({ group, name, version, classifier, path: artifact.path });
}

const g = 'net.neoforged';
const a = 'minecraft-dependencies';
const v = mcVersion;
const dir = path.join(outRoot, ...g.split('.'), a, v);
fs.mkdirSync(dir, { recursive: true });

// 记录一份原始清单，便于排查
fs.writeFileSync(path.join(dir, `${a}-${v}.libraries.json`), JSON.stringify(deps, null, 2));

// POM 是权威来源：带 classifier 的依赖 + systemPath 不可行，
// 所以这里改用 Gradle Module Metadata 描述文件，POM 只保留不带 classifier 的。
const plainDeps = deps.filter((d) => !d.classifier);
const pom = `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>${g}</groupId>
  <artifactId>${a}</artifactId>
  <version>${v}</version>
  <packaging>pom</packaging>
  <name>${a}</name>
  <description>Generated locally from the Mojang version manifest for ${mcVersion}</description>
  <dependencies>
${plainDeps.map((d) => `    <dependency><groupId>${d.group}</groupId><artifactId>${d.name}</artifactId><version>${d.version}</version></dependency>`).join('\n')}
  </dependencies>
</project>
`;
fs.writeFileSync(path.join(dir, `${a}-${v}.pom`), pom);

const moduleDeps = (d) => ({
  group: d.group,
  module: d.name,
  version: { requires: d.version },
  ...(d.classifier
    ? { attributes: { 'org.gradle.libraryelements': 'jar', 'org.gradle.docstype': 'unknown', classifier: d.classifier } }
    : {}),
});

// 带 classifier 的依赖用 ModuleMetadata 表达
const moduleJson = {
  formatVersion: '1.1',
  component: { group: g, module: a, version: v, attributes: { 'org.gradle.status': 'release' } },
  createdBy: { gradle: { version: '8.14.5' } },
  variants: [
    {
      name: 'apiElements',
      attributes: {
        'org.gradle.category': 'library',
        'org.gradle.dependency.bundling': 'external',
        'org.gradle.jvm.environment': 'standard-jvm',
        'org.gradle.jvm.version': 25,
        'org.gradle.libraryelements': 'jar',
        'org.gradle.usage': 'java-api',
      },
      dependencies: deps.map(moduleDeps),
    },
  ],
};

fs.writeFileSync(path.join(dir, `${a}-${v}.module`), JSON.stringify(moduleJson, null, 2));

console.log(`generated ${deps.length} libs (${deps.filter((d) => d.classifier).length} with classifier) -> ${dir}`);
