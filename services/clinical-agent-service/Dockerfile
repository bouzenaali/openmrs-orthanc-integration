FROM python:3.11-slim

# No GPU layer here on purpose: Phase 2's interpreter is deterministic and CPU-only. The image
# gains a model runtime in Phase 3, when MedGemma 4B replaces the rule-based NLU behind the same
# interface (app/nlu/base.py).

WORKDIR /srv/agent

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# The service holds no credentials of its own and no data; it does not need root.
RUN useradd --system --create-home --uid 10001 agent
USER agent

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS http://localhost:8000/health || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
