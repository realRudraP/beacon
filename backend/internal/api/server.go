package api

import (
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/jackc/pgx/v5/pgxpool"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
)

type Config struct {
	DB     *pgxpool.Pool
	Logger *slog.Logger
}
type Server struct {
	store  Store
	logger *slog.Logger
}

func NewServer(cfg Config) *Server {
	store := database.New(cfg.DB)
	return &Server{
		store:  store,
		logger: cfg.Logger,
	}
}

func (s *Server) Routes() http.Handler {
	r := chi.NewRouter()

	r.Use(middleware.Logger)
	r.Use(middleware.Recoverer)
	r.Use(middleware.RequestID)
	r.Use(middleware.Timeout(60 * time.Second))

	r.Route("/api/v1", func(r chi.Router) {
		r.Route("/users", func(r chi.Router) {
			r.Post("/register", s.RegisterUser)
		})
	})

	return r
}
