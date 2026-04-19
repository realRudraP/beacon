package main

import (
	"context"
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
	pool, err := pgxpool.New(ctx, os.Getenv("DATABASE_URL"))
	loggerConfig := slog.HandlerOptions{
		AddSource: true,
		Level:     slog.LevelDebug,
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
