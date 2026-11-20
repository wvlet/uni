# Rack on Puma: POST /example.rpc.TodoApi/list, same JSON and gzip rule as uni
require 'json'
items = Integer(ENV.fetch('ITEMS', '1'))
TODOS = (1..items).to_h { |i| [i, { id: i, title: i == 1 ? 'x' : "todo item number #{i - 1} with some text", done: false }] }
use Rack::Deflater, if: ->(_env, _status, _headers, body) { body.sum(&:bytesize) >= 1024 }
run ->(env) {
  JSON.parse(env['rack.input'].read)
  body = JSON.generate(TODOS.values.sort_by { |t| t[:id] })
  [200, { 'content-type' => 'application/json', 'x-rpc-status' => '0' }, [body]]
}
