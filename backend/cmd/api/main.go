package main

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"log"
	"log/slog"
	"net/http"
	"os"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"gitlab.com/realRudraP/beacon/backend/internal/api"
	"gitlab.com/realRudraP/beacon/backend/internal/config"
	"gitlab.com/realRudraP/beacon/backend/internal/token"
)

func main() {
	ctx := context.Background()

	envCfg, err := config.LoadEnvConfig()
	if err != nil {
		log.Fatalf("failed to load env config: %v", err)
	}

	logLevel := slog.LevelInfo
	if envCfg.Infra.DeploymentEnv == "development" {
		logLevel = slog.LevelDebug
	}

	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		AddSource: true,
		Level:     logLevel,
	}))

	if envCfg.Infra.DeploymentEnv == "development" {
		if envCfg.Token.JWTMintSecretToken == "" {
			logger.Warn("JWT_MINT_SECRET not set, using random value — sessions will invalidate on restart")
			envCfg.Token.JWTMintSecretToken = randomSecret()
		}
		if envCfg.Token.RefreshTokenHashSecret == "" {
			logger.Warn("REFRESH_TOKEN_HASH_SECRET not set, using random value — sessions will invalidate on restart")
			envCfg.Token.RefreshTokenHashSecret = randomSecret()
		}
	}

	pool, err := pgxpool.New(ctx, envCfg.DB.DSN())
	if err != nil {
		logger.Error("could not connect to DB", "error", err)
		os.Exit(1)
	}
	defer pool.Close()

	if err := pool.Ping(ctx); err != nil {
		logger.Error("could not ping DB", "error", err)
		os.Exit(1)
	}
	logger.Info("successfully connected to DB")

	appCfg, err := config.Load("config.yaml")
	if err != nil {
		logger.Error("could not load app config", "error", err)
		os.Exit(1)
	}

	tokenMaker := token.NewJWTMaker(envCfg.Token.JWTMintSecretToken)

	server := api.NewServer(api.Config{
		DB:         pool,
		Logger:     logger,
		TokenMaker: tokenMaker,
		AppConfig:  appCfg,
		EnvConfig:  envCfg,
	})

	httpServer := &http.Server{
		Addr:         fmt.Sprintf(":%d", envCfg.Infra.Port),
		Handler:      server.Routes(),
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 10 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	logger.Info("starting server", "port", envCfg.Infra.Port, "env", envCfg.Infra.DeploymentEnv)
	if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		logger.Error("server error", "error", err)
		os.Exit(1)
	}
}

func randomSecret() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		log.Fatalf("could not generate random secret: %v", err)
	}
	return base64.StdEncoding.EncodeToString(b)
}
