const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = 8080;
const HTML_PATH = path.join(__dirname, 'hu-password.html');
const UPDATE_ZIP_PATH = path.join(__dirname, 'workspace_injection', 'output', 'update.zip');
const REVERT_ZIP_PATH = path.join(__dirname, 'workspace_injection', 'revert_package', 'update_revert.zip');

function getLocalIp() {
  const nets = os.networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const net of nets[name]) {
      if (net.family === 'IPv4' && !net.internal && net.address.startsWith('192.168.')) {
        return net.address;
      }
    }
  }
  for (const name of Object.keys(nets)) {
    for (const net of nets[name]) {
      if (net.family === 'IPv4' && !net.internal && !net.address.startsWith('169.254.')) {
        return net.address;
      }
    }
  }
  return 'localhost';
}

function streamFile(filePath, downloadName, res) {
  if (!fs.existsSync(filePath)) {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('File not found: ' + downloadName);
    return;
  }
  const stat = fs.statSync(filePath);
  res.writeHead(200, {
    'Content-Type': 'application/zip',
    'Content-Length': stat.size,
    'Content-Disposition': `attachment; filename="${downloadName}"`,
    'Cache-Control': 'no-cache',
  });
  const stream = fs.createReadStream(filePath);
  stream.pipe(res);
}

const server = http.createServer((req, res) => {
  const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = parsedUrl.pathname;

  if (pathname === '/' || pathname === '/hu-password.html') {
    fs.readFile(HTML_PATH, (err, data) => {
      if (err) {
        res.writeHead(500, { 'Content-Type': 'text/plain' });
        res.end('Error loading hu-password.html: ' + err.message);
        return;
      }
      res.writeHead(200, {
        'Content-Type': 'text/html; charset=utf-8',
        'Cache-Control': 'no-cache, no-store, must-revalidate',
      });
      res.end(data);
    });
  } else if (pathname === '/update.zip' || pathname === '/download/update.zip') {
    streamFile(UPDATE_ZIP_PATH, 'update.zip', res);
  } else if (pathname === '/update_revert.zip' || pathname === '/download/update_revert.zip') {
    streamFile(REVERT_ZIP_PATH, 'update_revert.zip', res);
  } else {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not Found');
  }
});

server.listen(PORT, '0.0.0.0', () => {
  const ip = getLocalIp();
  console.log(`====================================================`);
  console.log(`  IHU Password & OTA File Server RUNNING!`);
  console.log(`  - Laptop URL : http://localhost:${PORT}`);
  console.log(`  - Phone URL  : http://${ip}:${PORT}`);
  console.log(`  - Download Wi-Fi Hook : http://${ip}:${PORT}/update.zip`);
  console.log(`  - Download Revert     : http://${ip}:${PORT}/update_revert.zip`);
  console.log(`====================================================`);
});

