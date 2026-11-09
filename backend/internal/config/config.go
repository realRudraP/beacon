package config

import (
	"os"
	"time"

	"gopkg.in/yaml.v3"
)

type BrandingConfig struct {
	BusinessName string `yaml:"business_name" json:"business_name"`
	LogoURL      string `yaml:"logo_url" json:"logo_url"`
}

type AppConfig struct {
	App struct {
		Port        int    `yaml:"port" json:"port"`
		Environment string `yaml:"environment" json:"environment"`
		LogLevel    string `yaml:"log_level" json:"log_level"`
		Timezone    string `yaml:"timezone" json:"timezone"`
	} `yaml:"app" json:"app"`

	Branding BrandingConfig `yaml:"branding" json:"branding"`

	Features struct {
		AutoDeletePhotoDays int `yaml:"auto_delete_attendance_photos_days" json:"auto_delete_attendance_photos_days"`
	} `yaml:"features" json:"features"`
}

// Location returns the configured IANA reporting timezone, falling back to UTC
// when unset or invalid. All persisted timestamps remain UTC; this is only used
// for interpreting and presenting dates in the admin UI.
func (c *AppConfig) Location() *time.Location {
	if c == nil || c.App.Timezone == "" {
		return time.UTC
	}
	loc, err := time.LoadLocation(c.App.Timezone)
	if err != nil {
		return time.UTC
	}
	return loc
}

func Load(filepath string) (*AppConfig, error) {
	file, err := os.ReadFile(filepath)
	if err != nil {
		return nil, err
	}

	var cfg AppConfig
	err = yaml.Unmarshal(file, &cfg)
	if err != nil {
		return nil, err
	}

	return &cfg, nil
}
