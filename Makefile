.PHONY: help setup pull-models run stop rebuild clean logs status purge-old-models

OLLAMA_URL := http://localhost:11434

# ─────────────────────────────────────────────────────────────────────────────
help:
	@echo ""
	@echo "The Vault v2 — Available Commands"
	@echo ""
	@echo "  make setup              First-time setup: pull AI models, build image, start services"
	@echo "  make run                Start all services (after first setup)"
	@echo "  make stop               Stop all services, preserve all data"
	@echo "  make rebuild            Rebuild the Spring Boot image and restart the app"
	@echo "  make clean              Stop services and DELETE all data (vectors, history)"
	@echo "  make logs               Follow live application logs"
	@echo "  make status             Check whether all three services are reachable"
	@echo "  make purge-old-models   Remove llama3 and nomic-embed-text to free disk space"
	@echo ""

# ─────────────────────────────────────────────────────────────────────────────
setup: check-docker check-ollama pull-models
	@echo ""
	@echo "Building application image (first build downloads ~300 MB of Maven deps)..."
	docker-compose up -d --build
	@echo ""
	@echo "Waiting for services to become healthy..."
	@sleep 10
	@$(MAKE) status
	@echo ""
	@echo "Setup complete. Open http://localhost:8081 in your browser."
	@echo ""

# ─────────────────────────────────────────────────────────────────────────────
run:
	docker-compose up -d
	@echo "Services started. UI: http://localhost:8081"

stop:
	docker-compose stop
	@echo "Services stopped. Data preserved in Docker volumes."

rebuild:
	docker-compose up -d --build app
	@echo "Application image rebuilt and restarted."

clean:
	docker-compose down -v
	@echo "All containers and volumes removed."
	@echo "NOTE: Re-ingest all documents after make clean — ChromaDB was wiped."

logs:
	docker-compose logs -f app

# ─────────────────────────────────────────────────────────────────────────────
status:
	@echo "=== Service Status ==="
	@printf "  Ollama    (port 11434): "; \
	  curl -s --max-time 3 $(OLLAMA_URL) > /dev/null 2>&1 && echo "ONLINE" || echo "OFFLINE"
	@printf "  ChromaDB  (port 8888):  "; \
	  curl -s --max-time 3 http://localhost:8888/api/v1/heartbeat > /dev/null 2>&1 && echo "ONLINE" || echo "OFFLINE"
	@printf "  App       (port 8081):  "; \
	  curl -s --max-time 5 http://localhost:8081/api/status > /dev/null 2>&1 && echo "ONLINE" || echo "OFFLINE"
	@echo ""

# ─────────────────────────────────────────────────────────────────────────────
check-docker:
	@docker info > /dev/null 2>&1 || (echo "ERROR: Docker is not running. Start Docker Desktop and retry."; exit 1)

check-ollama:
	@curl -s --max-time 3 $(OLLAMA_URL) > /dev/null 2>&1 || \
	  (echo ""; \
	   echo "ERROR: Ollama is not running on $(OLLAMA_URL)"; \
	   echo ""; \
	   echo "  Install:  curl -fsSL https://ollama.com/install.sh | sh"; \
	   echo "  Start:    ollama serve"; \
	   echo ""; \
	   exit 1)

pull-models:
	@echo "Pulling AI models (one-time download, may take several minutes)..."
	@echo "  gemma2 (9B) — ~5.5 GB — generation + CRAG evaluation"
	ollama pull gemma2
	@echo "  mxbai-embed-large — ~670 MB — document + query embeddings"
	ollama pull mxbai-embed-large
	@echo "Models ready."

# Remove old models to reclaim disk space.
# Only run this AFTER setup is confirmed working with the new models.
purge-old-models:
	@echo "Removing old models (llama3, nomic-embed-text)..."
	-ollama rm llama3 2>/dev/null || echo "  llama3 not found (already removed or never installed)"
	-ollama rm nomic-embed-text 2>/dev/null || echo "  nomic-embed-text not found (already removed)"
	@echo "Done. Run 'ollama list' to verify."
