import axios from "axios";
import { useAuthStore } from "@/store/authStore";

const api = axios.create({
  baseURL: "/api/v1",
});

// Inject the access token into every request
api.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token) {
    config.headers["Authorization"] = `Bearer ${token}`;
  }
  return config;
});

// State variables for the refresh queue
let isRefreshing = false;
let failedQueue: Array<{
  resolve: (token: string) => void;
  reject: (error: any) => void;
}> = [];

// Helper to process all waiting requests once the token arrives
const processQueue = (error: any, token: string | null = null) => {
  failedQueue.forEach((prom) => {
    if (error) {
      prom.reject(error);
    } else {
      prom.resolve(token as string);
    }
  });
  failedQueue = [];
};

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;

    if (error.response?.status === 401 && !originalRequest._retry) {
      // 1. If we are already refreshing, queue this request!
      if (isRefreshing) {
        return new Promise(function (resolve, reject) {
          failedQueue.push({ resolve, reject });
        })
          .then((token) => {
            originalRequest.headers["Authorization"] = `Bearer ${token}`;
            return api(originalRequest);
          })
          .catch((err) => {
            return Promise.reject(err);
          });
      }

      // 2. Lock the interceptor and start refreshing
      originalRequest._retry = true;
      isRefreshing = true;

      try {
        const { refreshToken, setTokens } = useAuthStore.getState();

        // FIX: Use the global axios but specify the full relative path
        const { data } = await axios.post(`/api/v1/auth/refresh`, {
          refresh_token: refreshToken,
          device_info: window.navigator.userAgent,
        });

        const { access_token, refresh_token: newRefreshToken } = data;
        setTokens(access_token, newRefreshToken ?? refreshToken);

        // 3. Resolve all queued requests with the new token
        processQueue(null, access_token);

        // 4. Retry the original request
        originalRequest.headers.Authorization = `Bearer ${access_token}`;
        return api(originalRequest);
      } catch (err) {
        // 5. If refresh fails, reject the queue and log the user out
        processQueue(err, null);
        useAuthStore.getState().clearTokens();
        window.location.href = "/login";
        return Promise.reject(err);
      } finally {
        isRefreshing = false;
      }
    }

    return Promise.reject(error);
  },
);

export default api;
