FROM golang:1.25.8-alpine AS builder

# Install the SSL CA certificates and git, needed to fetch dependencies
RUN apk update && apk add --no-cache ca-certificates git

# Set the working directory inside the container
WORKDIR /app

# Copy the go.mod and go.sum files to the working directory
# Doing this first helps in caching the dependencies layer, which speeds up subsequent builds
COPY go.mod go.sum ./
RUN go mod download

# Copy the rest of the application source code to the working directory
COPY . .

# Build the statically linked Go application
# CGO_ENABLED=0 ensures that the binary is statically linked, which is important for running in a minimal Alpine image
# -ldflags="-s -w" reduces the size of the binary by stripping debug information
RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -ldflags="-s -w" -o beacon-api ./cmd/api

# Stage 2: The runner of the production image
FROM alpine:latest

# Install the SSL CA certificates, needed for HTTPS requests
# Add tzdata for timezone support
RUN apk --no-cache add ca-certificates tzdata

WORKDIR /app

# Copy the statically linked binary from the builder stage
COPY --from=builder /app/beacon-api .

# Expost the port on which the application will listen internally
EXPOSE 8080

CMD ["./beacon-api"]
