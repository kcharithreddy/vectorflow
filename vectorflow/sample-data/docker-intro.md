# Docker Containerization Fundamentals

## What is Docker?

Docker is a platform for developing, shipping, and running applications in containers. Containers are lightweight, standalone, executable packages that include everything needed to run an application: code, runtime, system tools, libraries, and settings.

## Core Concepts

### Images

A Docker image is a read-only template with instructions for creating a Docker container. Images are built from Dockerfiles and can be stored in registries like Docker Hub.

Key characteristics:
- Immutable and versioned
- Built in layers for efficiency
- Can be based on other images
- Portable across environments

### Containers

A container is a runnable instance of an image. Containers are isolated from each other and the host system, but can share the kernel and resources.

Container lifecycle:
1. **Create**: Initialize from an image
2. **Start**: Begin execution
3. **Stop**: Gracefully terminate
4. **Remove**: Delete the container

### Dockerfile

A Dockerfile is a text document containing commands to assemble an image:

```dockerfile
# Base image
FROM python:3.11-slim

# Set working directory
WORKDIR /app

# Copy requirements first (for caching)
COPY requirements.txt .

# Install dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY . .

# Expose port
EXPOSE 8000

# Command to run
CMD ["python", "app.py"]
```

### Dockerfile Best Practices

1. **Use specific base image tags**: `python:3.11-slim` instead of `python:latest`
2. **Order instructions by frequency of change**: Copy requirements before code
3. **Minimize layers**: Combine RUN commands with &&
4. **Use .dockerignore**: Exclude unnecessary files
5. **Don't run as root**: Create a non-root user

## Docker Commands

### Image Operations

```bash
# Build an image
docker build -t myapp:1.0 .

# List images
docker images

# Remove an image
docker rmi myapp:1.0

# Pull from registry
docker pull nginx:latest

# Push to registry
docker push myregistry/myapp:1.0
```

### Container Operations

```bash
# Run a container
docker run -d -p 8080:80 --name mycontainer nginx

# List running containers
docker ps

# List all containers
docker ps -a

# Stop a container
docker stop mycontainer

# Start a stopped container
docker start mycontainer

# Remove a container
docker rm mycontainer

# View logs
docker logs mycontainer

# Execute command in container
docker exec -it mycontainer bash
```

## Docker Compose

Docker Compose is a tool for defining and running multi-container applications.

### docker-compose.yml Example

```yaml
version: '3.8'

services:
  web:
    build: ./web
    ports:
      - "8080:8080"
    environment:
      - DATABASE_URL=postgres://db:5432/myapp
    depends_on:
      - db
      - redis

  db:
    image: postgres:15
    volumes:
      - postgres_data:/var/lib/postgresql/data
    environment:
      - POSTGRES_PASSWORD=secret

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

volumes:
  postgres_data:
```

### Compose Commands

```bash
# Start all services
docker-compose up

# Start in background
docker-compose up -d

# Stop all services
docker-compose down

# View logs
docker-compose logs -f

# Rebuild services
docker-compose build

# Scale a service
docker-compose up -d --scale web=3
```

## Volumes and Persistence

### Volume Types

1. **Named Volumes**: Managed by Docker
2. **Bind Mounts**: Map host directory to container
3. **tmpfs Mounts**: Stored in memory only

```bash
# Create a named volume
docker volume create mydata

# Run with volume
docker run -v mydata:/app/data myimage

# Bind mount
docker run -v /host/path:/container/path myimage

# List volumes
docker volume ls
```

## Networking

### Network Types

- **bridge**: Default network for containers
- **host**: Use host's network stack
- **none**: No networking
- **overlay**: Multi-host networking for Swarm

```bash
# Create a network
docker network create mynetwork

# Run container on network
docker run --network mynetwork myimage

# Connect existing container
docker network connect mynetwork mycontainer
```

## Environment Variables

Pass configuration via environment variables:

```bash
# Command line
docker run -e DATABASE_URL=postgres://localhost/db myimage

# From file
docker run --env-file .env myimage
```

## Health Checks

Define health checks to monitor container status:

```dockerfile
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1
```

## Security Best Practices

1. **Use official base images** from trusted sources
2. **Scan images for vulnerabilities** with tools like Trivy
3. **Run containers as non-root** users
4. **Use read-only filesystems** when possible
5. **Limit container resources** with --memory and --cpus
6. **Don't store secrets in images** - use environment variables or secrets management
7. **Keep images updated** with security patches

## Common Patterns

### Multi-Stage Builds

Reduce image size by using multi-stage builds:

```dockerfile
# Build stage
FROM node:18 AS builder
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build

# Production stage
FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
```

### Sidecar Pattern

Run helper containers alongside main application for logging, monitoring, or proxying.

### Init Containers

Use init containers to prepare the environment before the main container starts.

