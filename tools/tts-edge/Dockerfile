FROM python:3-alpine
WORKDIR /app
COPY tts_server.py /app/tts_server.py
RUN pip install --no-cache-dir edge-tts
EXPOSE 8123
CMD ["python", "/app/tts_server.py"]
