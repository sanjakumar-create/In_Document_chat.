.PHONY: help setup pull-models run stop rebuild clean logs status

OLLAMA_URL := http://localhost:11434

# ─────────────────────────────────────────────────────────────────────────────
help:
	@echo ""
	@echo "The Vault — Available Commands"
	@echo ""
	@echo "  make setup     First-time setup: pull AI models, build image, start services"
	@echo "  make run       Start all services (after first setup)"
	@echo "  make stop      Stop all services, preserve all data"
	@echo "  make rebuild   Rebuild the Spring Boot image and restart the app"
	@echo "  make clean     Stop services and DELETE all data (vectors, history)"
	@echo "  make logs      Follow live application logs"
	@echo "  make status    Check whether all three services are reachable"
	@echo ""

# ─────────────────────────────────────────────────────────────────────────────
setup: check-docker check-ollama pull-models
	@echo ""
	@echo "Building application image (first build downloads ~300 MB of Maven deps)..."
	docker-compose up -d --build
	@echo ""
	@echo "Waiting for services to become healthy..."
	@sleep 8
	@$(MAKE) status
	@echo ""
	@echo "Setup complete. Open http://localhost:8080 in your browser."
	@echo ""

# ─────────────────────────────────────────────────────────────────────────────
run:
	docker-compose up -d
	@echo "Services started. UI: http://localhost:8080"

stop:
	docker-compose stop
	@echo "Services stopped. Data preserved in Docker volumes."

rebuild:
	docker-compose up -d --build app
	@echo "Application image rebuilt and restarted."

clean:
	docker-compose down -v
	@echo "All containers and volumes removed."

logs:
	docker-compose logs -f app

# ─────────────────────────────────────────────────────────────────────────────
status:
	@echo "=== Service Status ==="
	@printf "  Ollama    (port 11434): "; \
	  curl -s --max-time 3 $(OLLAMA_URL) > /dev/null 2>&1 && echo "ONLINE" || echo "OFFLINE"
	@printf "  ChromaDB  (port 8888):  "; \
	  curl -s --max-time 3 http://localhost:8888/api/v1/heartbeat > /dev/null 2>&1 && echo "ONLINE" || echo "OFFLINE"
	@printf "  App       (port 8080):  "; \
	  curl -s --max-time 5 http://localhost:8080/api/status > /dev/null 2>&1 && echo "ONLINE" || echo "OFFLINE"
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
	ollama pull llama3
	ollama pull nomic-embed-text
	@echo "Models ready."
