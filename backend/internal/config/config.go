package config

import (
	"os"

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
	} `yaml:"app" json:"app"`

	Branding BrandingConfig `yaml:"branding" json:"branding"`

	Features struct {
		AutoDeletePhotoDays int `yaml:"auto_delete_attendance_photos_days" json:"auto_delete_attendance_photos_days"`
	} `yaml:"features" json:"features"`
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
