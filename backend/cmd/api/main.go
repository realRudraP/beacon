package main

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"errors"
	"fmt"
	"log"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"
	"gitlab.com/realRudraP/beacon/backend/internal/api"
	"gitlab.com/realRudraP/beacon/backend/internal/config"
	"gitlab.com/realRudraP/beacon/backend/internal/token"
	"gitlab.com/realRudraP/beacon/backend/sql/migrations"
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

	logger.Info("running DB migrations...")

	migrationDB, err := sql.Open("pgx", envCfg.DB.DSN())
	if err != nil {
		logger.Error("could not open DB for migrations", "error", err)
		os.Exit(1)
	}
	goose.SetBaseFS(migrations.FS)
	if err := goose.SetDialect("postgres"); err != nil {
		logger.Error("could not set goose dialect", "error", err)
		os.Exit(1)
	}
	if err := goose.Up(migrationDB, "."); err != nil {
		logger.Error("could not run DB migrations", "error", err)
		os.Exit(1)
	}
	logger.Info("DB migrations completed successfully")
	migrationDB.Close()

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

	go func() {
		logger.Info("starting server", "port", envCfg.Infra.Port, "env", envCfg.Infra.DeploymentEnv)
		if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("server error", "error", err)
			os.Exit(1)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Info("shutting down server gracefully...")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := httpServer.Shutdown(shutdownCtx); err != nil {
		logger.Error("server forced to shutdown", "error", err)
		os.Exit(1)
	}

	logger.Info("server exited properly")

}

func randomSecret() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		log.Fatalf("could not generate random secret: %v", err)
	}
	return base64.StdEncoding.EncodeToString(b)
}
