import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import { useEffect } from "react";
import { useAuthStore } from "@/store/authStore";
import { useUserStore } from "@/store/userStore";
import LoginPage from "./pages/LoginPage";
import Dashboard from "./pages/Dashboard";
import SetupPage from "./pages/SetupPage";
import { ThemeProvider } from "./components/theme-provider";
import { useSystemState } from "./context/ConfigContext";
import Unauthorized from "./pages/Unauthorized";
import UsersPage from "./pages/UsersPage";
import NotFound from "./pages/NotFound";
import GeofenceEditor from "./pages/Geofences";
import SettingsPage from "./pages/Settings";
import AttendancePage from "./pages/Attendance";
import CashPage from "./pages/CashPage";
import WorkerCashDetailPage from "./pages/WorkerCashDetailPage";
import { AppLayout } from "./components/AppLayout";

function AdminRoute({ children }: { children: React.ReactNode }) {
  const { accessToken } = useAuthStore();
  const { user, loading } = useUserStore();
  if (!accessToken) return <Navigate to="/login" replace />;
  if (loading) return <div>Loading...</div>;
  if (!user) return <Navigate to="/login" replace />;
  if (user.role !== "admin" && user.role !== "superadmin")
    return <Navigate to="/unauthorized" replace />;
  return <>{children}</>;
}

export default function App() {
  const { accessToken } = useAuthStore();
  const { fetchUser } = useUserStore();
  const { needsSetup } = useSystemState();

  useEffect(() => {
    if (accessToken && !needsSetup) fetchUser();
  }, [accessToken, needsSetup, fetchUser]);

  return (
    <BrowserRouter>
      <ThemeProvider defaultTheme="dark" storageKey="vite-ui-theme">
        <Routes>
          {needsSetup ? (
            <>
              <Route path="/setup" element={<SetupPage />} />
              <Route path="*" element={<Navigate to="/setup" replace />} />
            </>
          ) : (
            <>
              {/* Public & Auth Routes */}
              <Route
                path="/"
                element={
                  accessToken ? (
                    <Navigate to="/dashboard" replace />
                  ) : (
                    <Navigate to="/login" replace />
                  )
                }
              />
              <Route path="/login" element={<LoginPage />} />
              <Route path="/unauthorized" element={<Unauthorized />} />

              {/* Protected Admin Routes with Shared AppLayout */}
              <Route
                element={
                  <AdminRoute>
                    <AppLayout />
                  </AdminRoute>
                }
              >
                <Route path="/dashboard" element={<Dashboard />} />
                <Route path="/attendance" element={<AttendancePage />} />
                <Route path="/cash" element={<CashPage />} />
                <Route path="/cash/:userId" element={<WorkerCashDetailPage />} />
                <Route path="/users" element={<UsersPage />} />
                <Route path="/geofences" element={<GeofenceEditor />} />
                <Route path="/settings" element={<SettingsPage />} />
              </Route>

              <Route path="/not-found" element={<NotFound />} />
              <Route path="*" element={<Navigate to="/not-found" replace />} />
            </>
          )}
        </Routes>
      </ThemeProvider>
    </BrowserRouter>
  );
}
