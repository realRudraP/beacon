package api

import (
	"log/slog"
	"net/http"
	"os"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/jackc/pgx/v5/pgxpool"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
	"gitlab.com/realRudraP/beacon/backend/internal/token"
)

type Config struct {
	DB         *pgxpool.Pool
	Logger     *slog.Logger
	TokenMaker token.Maker

	REFRESH_TOKEN_HASH_SECRET string
}
type Server struct {
	store      Store
	logger     *slog.Logger
	tokenMaker token.Maker

	REFRESH_TOKEN_HASH_SECRET string
}

func NewServer(cfg Config) *Server {
	store := database.New(cfg.DB)
	return &Server{
		store:      store,
		logger:     cfg.Logger,
		tokenMaker: cfg.TokenMaker,

		REFRESH_TOKEN_HASH_SECRET: cfg.REFRESH_TOKEN_HASH_SECRET,
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

	r.Route("/api/v1", func(r chi.Router) {
		r.Route("/users", func(r chi.Router) {
			r.Post("/register", s.RegisterUser)
			r.Group(func(r chi.Router) {
				r.Use(s.Authenticate)
				r.Get("/me", s.GetUserProfile)
			})
		})
		r.Route("/auth", func(r chi.Router) {
			r.Post("/login", s.LoginUser)
			r.Post("/refresh", s.HandleRefreshToken)
		})
	})

	return r
}
