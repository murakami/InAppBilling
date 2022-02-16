#!/usr/bin/env python
# -*- coding: utf-8 -*-

# Usage:
#   $ python3 server.py

from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

address = ('192.168.86.23', 8080)

class MyHTTPRequestHandler(BaseHTTPRequestHandler):
	def do_GET(self):
		print('path = {}'.format(self.path))
		parsed_path = urlparse(self.path)
		print('parsed: path = {}, query = {}'.format(parsed_path.path, parse_qs(parsed_path.query)))
		print('headers\r\n-----\r\n{}-----'.format(self.headers))
		self.send_response(200)
		self.send_header('Content-Type', 'text/plain; charset=utf-8')
		self.end_headers()
		self.wfile.write(b'Hello from do_GET')

	def do_POST(self):
		print('path = {}'.format(self.path))
		parsed_path = urlparse(self.path)
		print('parsed: path = {}, query = {}'.format(parsed_path.path, parse_qs(parsed_path.query)))
		print('headers\r\n-----\r\n{}-----'.format(self.headers))
		content_length = int(self.headers['content-length'])
		print('body = {}'.format(self.rfile.read(content_length).decode('utf-8')))
		self.send_response(200)
		self.send_header('Content-Type', 'text/plain; charset=utf-8')
		self.end_headers()
		self.wfile.write(b'Hello from do_POST')

with HTTPServer(address, MyHTTPRequestHandler) as server:
	server.serve_forever()
