const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = 8080;
const HTML_PATH = path.join(__dirname, 'hu-password.html');

function getLocalIp() {
  const nets = os.networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const net of nets[name]) {
      if (net.family === 'IPv4' && !net.internal) {
        return net.address;
      }
    }
  }
  return 'localhost';
}

const server = http.createServer((req, res) => {
  if (req.url === '/' || req.url === '/hu-password.html' || req.url.startsWith('/?')) {
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
  } else {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not Found');
  }
});

server.listen(PORT, '0.0.0.0', () => {
  const ip = getLocalIp();
  console.log(`====================================================`);
  console.log(`  IHU Password Server is RUNNING!`);
  console.log(`  - Laptop URL : http://localhost:${PORT}`);
  console.log(`  - Phone URL  : http://${ip}:${PORT}`);
  console.log(`====================================================`);
});
