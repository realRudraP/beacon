import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import { useEffect } from "react";
import { useAuthStore } from "@/store/authStore";
import { useUserStore } from "@/store/userStore";
import LoginPage from "./pages/LoginPage";
import Dashboard from "./pages/Dashboard";
import { ThemeProvider } from "./components/theme-provider";

function AdminRoute({ children }: { children: React.ReactNode }) {
  const { accessToken, clearTokens } = useAuthStore();
  const { user, loading, fetchUser } = useUserStore();

  useEffect(() => {
    if (accessToken && !user) {
      fetchUser();
    }
  }, [accessToken]);

  useEffect(() => {
    if (!loading) {
      if (!user) {
        clearTokens();
      } else if (user.role !== "admin") {
        clearTokens();
      }
    }
  }, [user, loading]);

  console.log("User: ", user, "Loading: ", loading);
  if (!accessToken) return <Navigate to="/login" replace />;
  if (loading) return <div>Loading...</div>;
  if (!user) return <Navigate to="/login" replace />;
  if (user.role !== "admin") return <Navigate to="/unauthorized" replace />;

  return <>{children}</>;
}

export default function App() {
  const { accessToken } = useAuthStore();
  const { fetchUser } = useUserStore();

  useEffect(() => {
    if (accessToken) fetchUser();
  }, [accessToken]);

  return (
    <BrowserRouter>
      <ThemeProvider defaultTheme="dark" storageKey="vite-ui-theme">
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route
            path="/dashboard"
            element={
              <AdminRoute>
                <Dashboard />
              </AdminRoute>
            }
          />
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </ThemeProvider>
    </BrowserRouter>
  );
}
