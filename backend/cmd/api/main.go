package main

import (
	"context"
	"crypto/rand"
	"log"
	"log/slog"
	"net/http"
	"os"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/joho/godotenv"
	"gitlab.com/realRudraP/beacon/backend/internal/api"
)

func main() {
	godotenv.Load()
	ctx := context.Background()
	deploymentEnv := os.Getenv("DEPLOYMENT_ENV")
	if deploymentEnv == "" {
		log.Fatal("DEPLOYMENT_ENV is not set")
	}

	if deploymentEnv != "production" && deploymentEnv != "development" {
		log.Fatal("DEPLOYMENT_ENV must be either 'production' or 'development'")
	}

	pool, err := pgxpool.New(ctx, os.Getenv("DATABASE_URL"))
	var logLevel slog.Level
	var REFRESH_TOKEN_HASH_SECRET string
	if deploymentEnv == "production" {
		REFRESH_TOKEN_HASH_SECRET = os.Getenv("REFRESH_TOKEN_HASH_SECRET")
		if REFRESH_TOKEN_HASH_SECRET == "" {
			log.Fatal("REFRESH_TOKEN_HASH_SECRET is not set")
			os.Exit(1)
		}
		logLevel = slog.LevelInfo
	} else {
		REFRESH_TOKEN_HASH_SECRET = os.Getenv("REFRESH_TOKEN_HASH_SECRET")
		if REFRESH_TOKEN_HASH_SECRET == "" {
			log.Print("REFRESH_TOKEN_HASH_SECRET is not set, using random value for development. Sessions might be invalidated on server restart.")
			b := make([]byte, 32)
			_, err := rand.Read(b)
			REFRESH_TOKEN_HASH_SECRET = string(b)
			if err != nil {
				log.Fatal("Could not generate random value for REFRESH_TOKEN_HASH_SECRET", "error", err)
				os.Exit(1)
			}
		}
		logLevel = slog.LevelDebug
	}
	loggerConfig := slog.HandlerOptions{
		AddSource: true,
		Level:     logLevel,
	}
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &loggerConfig))
	if err != nil {
		logger.Error("Could not connect to DB", "error", err)
		os.Exit(1)
	}
	defer pool.Close()

	err = pool.Ping(ctx)
	if err != nil {
		logger.Error("Could not ping DB", "error", err)
		os.Exit(1)
	}
	logger.Info("Successfully connected to DB")

	serverConfig := api.Config{
		DB:     pool,
		Logger: logger,

		REFRESH_TOKEN_HASH_SECRET: REFRESH_TOKEN_HASH_SECRET,
	}
	server := api.NewServer(serverConfig)

	httpServer := &http.Server{
		Addr:         ":8080",
		Handler:      server.Routes(),
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 10 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	logger.Info("Starting server on :8080")
	if err := httpServer.ListenAndServe(); err != nil {
		logger.Error("error in starting server", "err", err)
		os.Exit(1)
	}
}
