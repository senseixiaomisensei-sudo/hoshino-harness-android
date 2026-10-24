"""Regression for a full cached/partial source served without HTTP Range support."""
import http.server,pathlib,sys,tempfile,threading,unittest
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'rootfs'))
from prepare import download

class DownloadTest(unittest.TestCase):
    def test_non_range_server_can_refresh_the_next_abi_source(self):
        payload=b'locked-original-source'
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                if self.headers.get('Range'):
                    self.send_response(416);self.end_headers();return
                self.send_response(200);self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
            def log_message(self,*args):pass
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            with tempfile.TemporaryDirectory() as folder:
                path=pathlib.Path(folder)/'source.tgz';url=f'http://127.0.0.1:{server.server_port}/source'
                self.assertEqual(payload,download(url,path))
                self.assertEqual(payload,download(url,path))
                path.write_bytes(b'partial')
                self.assertEqual(payload,download(url,path))
        finally:server.shutdown();server.server_close();thread.join()

if __name__=='__main__':unittest.main()
