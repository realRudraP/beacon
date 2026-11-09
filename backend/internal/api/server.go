package api

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"time"

	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/messaging"
	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/go-chi/cors"
	"github.com/jackc/pgx/v5/pgxpool"
	"gitlab.com/realRudraP/beacon/backend/internal/config"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
	"gitlab.com/realRudraP/beacon/backend/internal/token"
	"google.golang.org/api/option"
)

type Config struct {
	DB         *pgxpool.Pool
	Logger     *slog.Logger
	TokenMaker token.Maker
	AppConfig  *config.AppConfig
	EnvConfig  *config.EnvConfig

	REFRESH_TOKEN_HASH_SECRET string
}
type Server struct {
	store      Store
	logger     *slog.Logger
	tokenMaker token.Maker
	AppConfig  *config.AppConfig
	EnvConfig  *config.EnvConfig
	fcmClient  *messaging.Client

	REFRESH_TOKEN_HASH_SECRET string
}

func NewServer(cfg Config) *Server {
	store := database.New(cfg.DB)
	s := &Server{
		store:      store,
		logger:     cfg.Logger,
		tokenMaker: cfg.TokenMaker,
		AppConfig:  cfg.AppConfig,
		EnvConfig:  cfg.EnvConfig,
	}
	credsPath := os.Getenv("FIREBASE_CREDENTIALS_FILE")
	if credsPath != "" {
		opt := option.WithCredentialsFile(credsPath)
		app, err := firebase.NewApp(context.Background(), nil, opt)
		if err != nil {
			s.logger.Error("failed to create Firebase app", "err", err)
		} else {
			fcmClient, err := app.Messaging(context.Background())
			if err != nil {
				s.logger.Error("failed to create FCM client", "err", err)
			} else {
				s.fcmClient = fcmClient
				s.logger.Info("FCM client initialized successfully")
			}
		}
	} else {
		s.logger.Warn("FIREBASE_CREDENTIALS_FILE not set, notifications are disabled")
	}
	return s
}

func NewTestServer(store Store, tokenMaker token.Maker) *Server {
	return &Server{
		store:      store,
		logger:     slog.New(slog.NewTextHandler(os.Stdout, nil)),
		tokenMaker: tokenMaker,
	}
}

func (s *Server) Routes() http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.Logger)
	r.Use(middleware.Recoverer)
	r.Use(middleware.RequestID)
	r.Use(middleware.Timeout(60 * time.Second))
	r.Use(cors.Handler(cors.Options{
		AllowedOrigins:   []string{"https://*", "http://*"},
		AllowedMethods:   []string{"GET", "POST", "PUT", "DELETE", "OPTIONS"},
		AllowedHeaders:   []string{"Accept", "Authorization", "Content-Type", "X-CSRF-Token"},
		ExposedHeaders:   []string{"Link"},
		AllowCredentials: true,
		MaxAge:           300,
	}))
	uploadDir := "./data/uploads/photos"
	fileServer := http.StripPrefix("/static/photos/", http.FileServer(http.Dir(uploadDir)))
	r.Group(func(r chi.Router) {
		r.Use(s.Authenticate)
		r.Get("/static/photos/*", func(w http.ResponseWriter, r *http.Request) {
			fileServer.ServeHTTP(w, r)
		})
	})
	r.Route("/api/v1", func(r chi.Router) {
		r.Route("/attendance", func(r chi.Router) {
			r.Group(func(r chi.Router) {
				r.Use(s.Authenticate)
				r.Post("/", s.handleMarkAttendance)
				r.Get("/", s.handleGetMyAttendance)
				r.Patch("/{id}", s.handleUpdateAttendance)
				r.Post("/photos/{id}", s.HandleUploadAttendancePhoto)
				r.Post("/location-checks", s.handleIngestLocationChecks)
			})
			r.Route("/admin", func(r chi.Router) {
				r.Use(s.Authenticate)
				// TODO: add an admin/role-gating middleware here (e.g. s.RequireAdmin)
				// so workers can't hit these — Authenticate alone only checks login.
				r.Get("/", s.handleListAttendance)
				r.Get("/open", s.handleGetOpenAttendance)
				r.Get("/absent", s.handleGetAbsentUsers)
				r.Get("/stale", s.handleGetStaleOpenAttendance)
				r.Get("/location-checks", s.handleListLocationChecks)
			})
		})
		r.Route("/system", func(r chi.Router) {
			r.Get("/config", s.getConfig)
		})
		r.Route("/users", func(r chi.Router) {
			r.Group(func(r chi.Router) {
				r.Use(s.Authenticate)
				r.Get("/me", s.GetUserProfile)
				r.Get("/", s.GetAllUsers)
				r.Post("/", s.RegisterUser)
				r.Patch("/{username}", s.EditUser)
				r.Delete("/{username}", s.softDeleteUser)
				r.Post("/fcm-token", s.handleUpdateFCMToken)
			})
		})
		r.Route("/auth", func(r chi.Router) {
			r.Post("/login", s.LoginUser)
			r.Post("/refresh", s.HandleRefreshToken)
		})
		r.Route("/geofences", func(r chi.Router) {
			r.Use(s.Authenticate)
			r.Post("/", s.createGeofence)
			r.Get("/", s.getAllGeofences)
			r.Delete("/{id}", s.deleteGeofence)
		})
		r.Route("/setup", func(r chi.Router) {
			r.Use(s.requireNotInitialized)
			r.Post("/superadmin", s.HandleRegisterSuperAdmin)
		})
		r.Route("/config", func(r chi.Router) {
			r.Use(s.Authenticate)
			r.Get("/", s.HandleGetSyncConfig)
			r.Put("/", s.HandleUpdateSyncConfig)
		})
		r.Route("/cash", func(r chi.Router) {
			r.Use(s.Authenticate)
			r.Get("/transactions", s.handleGetMyCashTransactions)
			r.Post("/transactions", s.handleCreateCashTransaction)
			r.Get("/summary", s.handleGetWorkersCashSummary)
		})
	})

	return r
}
