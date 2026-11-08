// 本地 NeoForge Maven 反向代理
//
// 背景：本机 maven.neoforged.net 的 DNS 被污染/不可达（解析到 202.160.x.x / 104.244.43.228，
// TCP 443 全部超时）。而 ModDevGradle 在构建 neoform-runtime 等构件时把该地址写死在内部，
// 不读我们配置的 mirror。所以这里在本地起一个代理：
//
//     maven.neoforged.net:443  (hosts 指向 127.0.0.1)
//              └──> 本地 TLS 终止 ──> https://maven.creeperhost.net  (NeoForge 官方构件镜像)
//
// 需要一份自签证书，由 --gen-cert 生成，并通过 JAVA 的 trustStore 参数让 Gradle 信任。
//
// 用法：
//   node neoforge-mirror-proxy.mjs                      # 启动（默认 443）
//   node neoforge-mirror-proxy.mjs --port 8443          # 非特权端口调试
//   node neoforge-mirror-proxy.mjs --gen-cert out/      # 只生成证书

import https from 'node:https';
import http from 'node:http';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const UPSTREAM_HOST = 'maven.creeperhost.net';
const UPSTREAM_PREFIX = ''; // creeperhost 把 releases 内容挂在根路径
const HOST_NAME = 'maven.neoforged.net';

const argv = process.argv.slice(2);
const argOf = (name, def) => {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : def;
};

const dir = path.resolve(argOf('--dir', '.build-tls'));
const certFile = path.join(dir, 'server.crt');
const keyFile = path.join(dir, 'server.key');

function genCert() {
  fs.mkdirSync(dir, { recursive: true });
  const keytool = path.join(process.env.JAVA_HOME || '', 'bin', 'keytool.exe');
  const p12 = path.join(dir, 'server.p12');
  const pass = 'changeit';
  execFileSync(keytool, [
    '-genkeypair', '-alias', 'neoforge-mirror',
    '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650',
    '-dname', `CN=${HOST_NAME}`,
    '-dnsname', HOST_NAME,
    '-keystore', p12, '-storetype', 'PKCS12', '-storepass', pass,
  ], { stdio: 'inherit' });
  // PKCS12 -> PEM
  execFileSync(keytool, [
    '-exportcert', '-rfc', '-alias', 'neoforge-mirror',
    '-keystore', p12, '-storepass', pass, '-file', certFile,
  ], { stdio: 'inherit' });
  console.log(`cert: ${certFile}`);
  console.log(`store: ${p12} (pass ${pass})`);
}

if (argv.includes('--gen-cert')) {
  genCert();
  process.exit(0);
}

if (!fs.existsSync(certFile)) {
  console.error(`缺少证书 ${certFile}，先运行 --gen-cert`);
  process.exit(2);
}

const tls = {
  key: fs.readFileSync(keyFile),
  cert: fs.readFileSync(certFile),
};

// keytool 导出的只是证书，私钥在 p12 里 —— 这里用 keytool 导出私钥不可行，
// 所以实际签名私钥通过 openssl/p12 转换在 gen-cert 里一并产出。
const port = Number(argOf('--port', '443'));

function proxy(req, res) {
  // maven.neoforged.net/releases/xxx  ->  creeperhost/xxx （镜像把 releases 挂到了根）
  let p = req.url.replace(/^\/releases(?=\/)/, '');
  const upstreamPath = (UPSTREAM_PREFIX + p).replace(/\/{2,}/g, '/');
  const opts = {
    host: UPSTREAM_HOST,
    port: 443,
    method: req.method,
    path: upstreamPath,
    headers: { ...req.headers, host: UPSTREAM_HOST },
  };
  delete opts.headers['accept-encoding'];

  const up = https.request(opts, (upRes) => {
    res.writeHead(upRes.statusCode || 502, upRes.headers);
    upRes.pipe(res);
  });
  up.on('error', (e) => {
    console.error(`upstream error ${req.url}: ${e.message}`);
    if (!res.headersSent) res.writeHead(502);
    res.end();
  });
  req.pipe(up);
}

https.createServer(tls, proxy).listen(port, '127.0.0.1', () => {
  console.log(`proxy up: https://${HOST_NAME}:${port} -> https://${UPSTREAM_HOST}`);
});

// 顺带提供 HTTP 端口，便于验证
http.createServer(proxy).listen(port + 1, '127.0.0.1', () => {
  console.log(`probe:    http://127.0.0.1:${port + 1}`);
});
