import { createServer } from 'node:http';
import { setTimeout as pause } from 'node:timers/promises';
export function createMockLlm() {
  const requests = [];
  const server = createServer(async (req, res) => {
    const path = new URL(req.url, 'http://127.0.0.1').pathname;
    if (req.method === 'GET' && path === '/health') { res.writeHead(200, {'Content-Type':'application/json'}).end(JSON.stringify({requests:requests.length,paths:requests.map(r=>r.path)})); return; }
    if (req.method !== 'POST' || !['/v1/messages', '/v1/chat/completions'].includes(path)) { res.writeHead(404).end(); return; }
    let raw = ''; for await (const chunk of req) raw += chunk;
    const body = JSON.parse(raw); requests.push({ path, body });
    const anthropic = path === '/v1/messages';
    const messages = body.messages;
    const user = [...messages].reverse().find(m => m.role === 'user' && (typeof m.content === 'string' || m.content.some?.(c => c.type === 'text')));
    const prompt = typeof user?.content === 'string' ? user.content : user?.content.filter(c => c.type === 'text').map(c => c.text).join(' ') ?? '';
    const last = messages.at(-1);
    const hasResult = last?.role === 'tool' || last?.content?.some?.(c => c.type === 'tool_result');
    const tool = !hasResult && /设备|device/.test(prompt) ? 'get_device_info' : !hasResult && /hello\.txt/.test(prompt) ? 'write' : undefined;
    const args = tool === 'write' ? {file_path:'hello.txt', content:'Hello from dsh\n'} : {};
    const answer = hasResult ? '工具已完成。' : '你好，我是 Hoshino。';
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
    const event = async (data, name) => { res.write(`${name ? `event: ${name}\n` : ''}data: ${JSON.stringify(data)}\n\n`); await pause(15); };
    if (anthropic) {
      const send = (type, data = {}) => event({ type, ...data }, type);
      await send('message_start', {message:{id:'msg_mock', type:'message', role:'assistant', model:body.model, content:[], stop_reason:null, stop_sequence:null, usage:{input_tokens:10, output_tokens:0}}});
      await send('content_block_start', {index:0, content_block:{type:'thinking', thinking:''}});
      await send('content_block_delta', {index:0, delta:{type:'thinking_delta', thinking:'我先检查请求。'}});
      await send('content_block_delta', {index:0, delta:{type:'signature_delta', signature:'mock-signature'}});
      await send('content_block_stop', {index:0});
      if (tool) {
        await send('content_block_start', {index:1, content_block:{type:'tool_use', id:'call_mock', name:tool, input:{}}});
        await send('content_block_delta', {index:1, delta:{type:'input_json_delta', partial_json:JSON.stringify(args)}});
      } else {
        await send('content_block_start', {index:1, content_block:{type:'text', text:''}});
        for (const text of ['你好，',answer]) await send('content_block_delta', {index:1, delta:{type:'text_delta', text}});
      }
      await send('content_block_stop', {index:1});
      await send('message_delta', {delta:{stop_reason:tool ? 'tool_use':'end_turn', stop_sequence:null},usage:{output_tokens:20}});
      await send('message_stop');
    } else {
      const chunk = (delta, finish_reason = null) => event({id:'chat_mock', object:'chat.completion.chunk', created:1, model:body.model, choices:[{index:0, delta, finish_reason}]});
      await chunk({role:'assistant', content:''});
      await chunk({reasoning_content:'我先检查请求。'});
      if (tool) {
        await chunk({tool_calls:[{index:0, id:'call_mock', type:'function', function:{name:tool, arguments:''}}]});
        await chunk({tool_calls:[{index:0, function:{arguments:JSON.stringify(args)}}]});
      } else for (const content of ['你好，',answer]) await chunk({content});
      await chunk({}, tool ? 'tool_calls':'stop');
      res.write('data: [DONE]\n\n');
    }
    res.end();
  });
  return {server, requests};
}
if (process.argv[1] && import.meta.url === new URL(`file:///${process.argv[1].replaceAll('\\','/').replace(/^\//,'')}`).href) {
  const {server} = createMockLlm(); server.listen(Number(process.env.PORT ?? 8099), '127.0.0.1');
}
