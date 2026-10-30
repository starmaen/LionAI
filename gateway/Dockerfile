FROM python:3.12-slim

WORKDIR /app
COPY server.py requirements.txt ./

# Hugging Face Spaces uses 7860 by default; Render overrides PORT automatically.
ENV PORT=7860
EXPOSE 7860

CMD ["python3", "server.py"]
