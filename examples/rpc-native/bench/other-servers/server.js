// Node.js built-in http: POST /example.rpc.TodoApi/list, same JSON and gzip rule as uni
const http = require('http'), zlib = require('zlib'), cluster = require('cluster');
const port = +process.argv[2], workers = +(process.env.WORKERS || 1), items = +(process.env.ITEMS || 1);
if (cluster.isPrimary && workers > 1) { for (let i = 0; i < workers; i++) cluster.fork(); }
else {
  const todos = new Map();
  for (let i = 1; i <= items; i++) todos.set(i, {id: i, title: i === 1 ? 'x' : `todo item number ${i - 1} with some text`, done: false});
  http.createServer((req, res) => {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => {
      JSON.parse(Buffer.concat(chunks).toString() || '{}');
      const list = [...todos.values()].sort((a, b) => a.id - b.id);
      let body = Buffer.from(JSON.stringify(list));
      const headers = {'Content-Type': 'application/json', 'X-RPC-Status': '0'};
      if (body.length >= 1024 && /gzip/.test(req.headers['accept-encoding'] || '')) {
        body = zlib.gzipSync(body); headers['Content-Encoding'] = 'gzip'; headers['Vary'] = 'Accept-Encoding';
      }
      headers['Content-Length'] = body.length;
      res.writeHead(200, headers); res.end(body);
    });
  }).listen(port, '127.0.0.1');
}
