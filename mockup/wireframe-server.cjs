const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const root = path.join(__dirname, 'dist');
const port = 4174;
const server = http.createServer((req, res) => {
  const pathname = new URL(req.url, 'http://localhost').pathname;
  if (pathname === '/favicon.ico') { res.writeHead(204); res.end(); return; }
  let file;
  if (pathname === '/' || pathname === '/wireframe.html') file = 'wireframe.html';
  else if (/^\/wireframes\/(?:\d{2}\.html|manifest\.json)$/.test(pathname)) file = pathname.slice(1);
  else { res.writeHead(404); res.end('Not found'); return; }
  fs.readFile(path.join(root, file), (error, body) => {
    if (error) { res.writeHead(404); res.end('Not found'); return; }
    res.writeHead(200, {
      'Content-Type': file.endsWith('.json') ? 'application/json; charset=utf-8' : 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
      'Content-Security-Policy': "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-src 'self'; object-src 'none'; base-uri 'none'",
    });
    res.end(body);
  });
});
server.listen(port, '127.0.0.1', () => console.log('Chagok wireframes: http://127.0.0.1:' + port));
server.on('error', error => { console.error(error.message); process.exitCode = 1; });
