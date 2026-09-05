package api

import (
	"context"
	"time"

	"firebase.google.com/go/v4/messaging"
)

func (s *Server) sendPushNotification(fcmToken string, title string, body string) {
	if s.fcmClient == nil {
		s.logger.Debug("skipping push notification: fcmClient is nil")
		return
	}

	if fcmToken == "" {
		return
	}

	msg := &messaging.Message{
		Token: fcmToken,
		Notification: &messaging.Notification{
			Title: title,
			Body:  body,
		},
		Android: &messaging.AndroidConfig{
			Priority: "high",
			Notification: &messaging.AndroidNotification{
				Sound: "default",
			},
		},
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	response, err := s.fcmClient.Send(ctx, msg)
	if err != nil {
		s.logger.Error("failed to send FCM notification", "err", err, "token", fcmToken)
		return
	}

	s.logger.Info("FCM notification sent successfully", "message_id", response)
}
