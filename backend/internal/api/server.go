package api

import (
	"log/slog"
	"net/http"
	"os"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/go-chi/cors"
	"github.com/jackc/pgx/v5/pgxpool"
	"gitlab.com/realRudraP/beacon/backend/internal/config"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
	"gitlab.com/realRudraP/beacon/backend/internal/token"
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

	REFRESH_TOKEN_HASH_SECRET string
}

func NewServer(cfg Config) *Server {
	store := database.New(cfg.DB)
	return &Server{
		store:      store,
		logger:     cfg.Logger,
		tokenMaker: cfg.TokenMaker,
		AppConfig:  cfg.AppConfig,
		EnvConfig:  cfg.EnvConfig,
	}
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

	r.Route("/api/v1", func(r chi.Router) {
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
			})
		})
		r.Route("/auth", func(r chi.Router) {
			r.Post("/login", s.LoginUser)
			r.Post("/refresh", s.HandleRefreshToken)
		})
		r.Route("/setup", func(r chi.Router) {
			r.Use(s.requireNotInitialized)
			r.Post("/superadmin", s.HandleRegisterSuperAdmin)
		})
	})

	return r
}
