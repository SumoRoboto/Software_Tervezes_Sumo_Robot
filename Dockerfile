FROM python:3.11-slim

WORKDIR /app

COPY Backend/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY src /app/src

EXPOSE 8000

ENV PYTHONUNBUFFERED=1

CMD ["python", "src/backend/mock_server.py"]
