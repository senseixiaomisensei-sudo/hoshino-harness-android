import http.server,json,sys,threading
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(s):
        n=int(s.headers.get('Content-Length',0));b=s.rfile.read(n)
        m=s.path.strip('/');print('CALLBACK',m,b[:200],flush=True)
        r={'providers':[],'credential':{'value':''},'device-info':{'model':'ci','androidVersion':'15','battery':80}}.get(m,{})
        o=json.dumps(r).encode();s.send_response(200);s.send_header('Content-Type','application/json');s.send_header('Content-Length',str(len(o)));s.end_headers();s.wfile.write(o)
        if m=='ready':print('KERNEL_READY',flush=True);open('/tmp/ready','w').write('1')
    def log_message(s,*a):pass
srv=http.server.ThreadingHTTPServer(('127.0.0.1',47123),H);srv.serve_forever()
