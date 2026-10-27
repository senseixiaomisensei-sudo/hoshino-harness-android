import { createServer } from 'node:http';
import { setTimeout as pause } from 'node:timers/promises';
export function createMockLlm() {
  const requests = [];
  const server = createServer(async (req, res) => {
    const path = new URL(req.url, 'http://127.0.0.1').pathname;
    if (req.method === 'GET' && path === '/health') { res.writeHead(200, {'Content-Type':'application/json'}).end(JSON.stringify({requests:requests.length,paths:requests.map(r=>r.path),requestOptions:requests.map(r=>({model:r.body.model,effort:r.body.reasoning_effort??r.body.output_config?.effort,maxTokens:r.body.max_tokens??r.body.max_completion_tokens,maxTokensField:r.body.max_tokens!==undefined?'max_tokens':'max_completion_tokens',thinkingType:r.body.thinking?.type})),benchmarkRequests:requests.filter(r=>r.benchmark).length,promptClasses:requests.map(r=>r.promptClass)})); return; }
    if (req.method !== 'POST' || !['/v1/messages', '/v1/chat/completions'].includes(path)) { res.writeHead(404).end(); return; }
    let raw = ''; for await (const chunk of req) raw += chunk;
    const body = JSON.parse(raw); requests.push({ path, body });
    const anthropic = path === '/v1/messages';
    const messages = body.messages;
    const userTexts = messages.filter(m => m.role === 'user').map(m => typeof m.content === 'string' ? m.content : m.content?.filter?.(c => c.type === 'text').map(c => c.text).join(' ') ?? '');
    // dsh appends runtime context and the skill catalog after the real user message.
    const prompt = [...userTexts].reverse().find(text => /m2-desktop-|system command|系统命令|shizuku.*id|读取设备|\bdevice\b|hello\.txt|m1-error-fixture|message-needle-|m0-benchmark-|你好/i.test(text)) ?? userTexts.at(-1) ?? '';
    if (prompt.includes('m1-error-fixture')) {
      res.writeHead(400,{'Content-Type':'application/json'}).end(JSON.stringify({error:{type:'invalid_request_error',message:'fixture request rejected'}}));
      return;
    }
    const last = messages.at(-1);
    const hasResult = last?.role === 'tool' || last?.content?.some?.(c => c.type === 'tool_result');
    const tool = !hasResult && /m2-desktop-todo/.test(prompt)?'todo_write':!hasResult && /m2-desktop-read/.test(prompt)?'read':!hasResult && /m2-desktop-bash/.test(prompt)?'bash':!hasResult && /system command|系统命令|shizuku.*id/i.test(prompt) ? 'system_shell' : !hasResult && /设备|device/.test(prompt) ? 'get_device_info' : !hasResult && /hello\.txt/.test(prompt) ? 'write' : undefined;
    const writeMarker=prompt.match(/m1-write-fixture-[a-z]+(?:-[a-f0-9]+)?/)?.[0];
    const args = tool==='todo_write'?{todos:[{content:'Verify desktop plugin execution',status:'completed'}]}:tool==='read'?{file_path:'hello.txt'}:tool==='bash'?{command:'printf m2-desktop-plugin',description:'Verify the original desktop shell tool'}:tool === 'write' ? {file_path:'hello.txt', content:'Hello from dsh\n'+(writeMarker?writeMarker+'\n':'')} : tool==='system_shell' ? {command:'id -u; getprop ro.build.version.release',timeout_ms:5000} : {};
    const resultText = hasResult ? (typeof last.content === 'string' ? last.content : last.content.filter(c=>c.type==='tool_result').map(c=>typeof c.content==='string'?c.content:JSON.stringify(c.content)).join(' ')) : '';
    const answer = hasResult ? `工具已完成，返回结果：${resultText}` : '你好，我是 Hoshino。';
    // dsh may append another user-role context message; identify the fixture in the full request.
    const marker=JSON.stringify(messages).match(/m0-benchmark-[a-f0-9]{8}-[1-5]/ig)?.at(-1);
    const benchmark=marker!==undefined || /benchmark/i.test(prompt);
    requests.at(-1).benchmark=benchmark;
    requests.at(-1).promptClass={selectedTextLength:prompt.length,benchmarkInMessages:/benchmark/i.test(JSON.stringify(messages)),userCount:messages.filter(m=>m.role==='user').length,lastRole:last?.role};
    const textChunks=benchmark?[`M0:${marker??'benchmark'}`,...Array(60).fill('基准测试输出。')]:['你好，',answer];
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
        for (const text of textChunks) {await send('content_block_delta', {index:1, delta:{type:'text_delta', text}});if(benchmark)await pause(500);}
      }
      await send('content_block_stop', {index:1});
      await send('message_delta', {delta:{stop_reason:tool ? 'tool_use':'end_turn', stop_sequence:null},usage:{output_tokens:20}});
      await send('message_stop');
    } else {
      const chunk = (delta, finish_reason = null) => event({id:'chat_mock', object:'chat.completion.chunk', created:1, model:body.model, choices:[{index:0, delta, finish_reason}],...(finish_reason ? {usage:{prompt_tokens:10,completion_tokens:20,total_tokens:30,prompt_tokens_details:{cached_tokens:2},completion_tokens_details:{reasoning_tokens:5}}} : {})});
      await chunk({role:'assistant', content:''});
      await chunk({reasoning_content:'我先检查请求。'});
      if (tool) {
        await chunk({tool_calls:[{index:0, id:'call_mock', type:'function', function:{name:tool, arguments:''}}]});
        await chunk({tool_calls:[{index:0, function:{arguments:JSON.stringify(args)}}]});
      } else for (const content of textChunks) {await chunk({content});if(benchmark)await pause(500);}
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
