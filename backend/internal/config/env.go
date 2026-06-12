package config

import (
	"bufio"
	"fmt"
	"os"
	"strconv"
	"strings"
)

type InfraConfig struct {
	Domain        string
	Port          int
	DeploymentEnv string
}

type DBConfig struct {
	Host     string
	Port     int
	User     string
	Password string
	Name     string
	SSLMode  string
}

type TokenConfig struct {
	JWTMintSecretToken     string
	RefreshTokenHashSecret string
}

type EnvConfig struct {
	Infra InfraConfig
	DB    DBConfig
	Token TokenConfig
}

func LoadEnvConfig() (*EnvConfig, error) {
	loadDotEnv()

	infraPort, err := parseInt("PORT", "8080")
	if err != nil {
		return nil, err
	}
	dbPort, err := parseInt("DB_PORT", "5432")
	if err != nil {
		return nil, err
	}

	cfg := &EnvConfig{
		Infra: InfraConfig{
			Domain:        getEnv("DOMAIN", "localhost"),
			Port:          infraPort,
			DeploymentEnv: getEnv("DEPLOYMENT_ENV", "development"),
		},
		DB: DBConfig{
			Host:     getEnv("POSTGRES_HOST", "db"),
			Port:     dbPort,
			User:     getEnv("POSTGRES_USER", "beacon"),
			Password: getEnv("POSTGRES_PASSWORD", "changemeplease"),
			Name:     getEnv("POSTGRES_DB", "beacon_db"),
			SSLMode:  getEnv("DB_SSL_MODE", "disable"),
		},
		Token: TokenConfig{
			JWTMintSecretToken:     os.Getenv("JWT_MINT_SECRET"),
			RefreshTokenHashSecret: os.Getenv("REFRESH_TOKEN_HASH_SECRET"),
		},
	}

	if err := cfg.validate(); err != nil {
		return nil, err
	}

	return cfg, nil
}

func (c *EnvConfig) validate() error {
	if c.Infra.DeploymentEnv != "production" && c.Infra.DeploymentEnv != "development" {
		return fmt.Errorf("DEPLOYMENT_ENV must be 'production' or 'development', got %q", c.Infra.DeploymentEnv)
	}
	if c.Infra.DeploymentEnv == "production" {
		required := []struct{ value, name string }{
			{c.Token.JWTMintSecretToken, "JWT_MINT_SECRET"},
			{c.Token.RefreshTokenHashSecret, "REFRESH_TOKEN_HASH_SECRET"},
		}
		for _, r := range required {
			if r.value == "" {
				return fmt.Errorf("required environment variable %q is not set", r.name)
			}
		}
	}
	return nil
}
func getEnv(key, defaultVal string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return defaultVal
}

func parseInt(key, defaultVal string) (int, error) {
	raw := getEnv(key, defaultVal)
	v, err := strconv.Atoi(raw)
	if err != nil {
		return 0, fmt.Errorf("invalid value for %q: expected an integer, got %q", key, raw)
	}
	return v, nil
}

func loadDotEnv() {
	f, err := os.Open(".env")
	if err != nil {
		return
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		key = strings.TrimSpace(key)
		value = strings.TrimSpace(value)
		value = strings.Trim(value, `"'`)
		if os.Getenv(key) == "" {
			os.Setenv(key, value)
		}
	}
}
func (d DBConfig) DSN() string {
	return fmt.Sprintf(
		"host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
		d.Host, d.Port, d.User, d.Password, d.Name, d.SSLMode,
	)
}
