import { useState } from "react";
import { GalleryVerticalEnd, Loader2 } from "lucide-react";
import { useAuthStore } from "@/store/authStore";
import { useSystemState } from "@/context/ConfigContext";
import api from "@/api/axiosClient";

// Shadcn UI Imports
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

export default function SetupPage() {
  const { branding } = useSystemState();
  const { setTokens } = useAuthStore();

  const [loading, setLoading] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);

  const [formData, setFormData] = useState({
    full_name: "",
    username: "",
    email: "",
    password: "",
    confirm_password: "",
  });

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setFormData((prev) => ({ ...prev, [e.target.name]: e.target.value }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setServerError(null);

    if (formData.password !== formData.confirm_password) {
      setServerError("Passwords do not match.");
      return;
    }

    if (formData.password.length < 8) {
      setServerError("Password must be at least 8 characters long.");
      return;
    }

    setLoading(true);

    try {
      const payload = {
        full_name: formData.full_name,
        email: formData.email,
        username: formData.username,
        password: formData.password,
      };
      console.log("Register Superadmin payload: " + payload);
      const { data } = await api.post("/setup/superadmin", payload);

      setTokens(data.access_token, data.refresh_token);
      window.location.href = "/dashboard";
    } catch (err: any) {
      setServerError(
        err.response?.data?.message ||
          "Failed to initialize system. Check server logs.",
      );
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="grid min-h-svh lg:grid-cols-2">
      <div className="flex flex-col gap-4 p-6 md:p-10">
        {/* Top Branding */}
        <div className="flex justify-center gap-2 md:justify-start">
          <div className="flex items-center gap-2 font-medium">
            <div className="flex size-6 items-center justify-center rounded-md bg-primary text-primary-foreground">
              <GalleryVerticalEnd className="size-4" />
            </div>
            {branding.business_name}
          </div>
        </div>

        {/* Main Form Container */}
        <div className="flex flex-1 items-center justify-center">
          <div className="w-full max-w-md space-y-6">
            <div className="space-y-2 text-center md:text-left">
              <h1 className="text-2xl font-bold tracking-tight">
                System Initialization
              </h1>
              <p className="text-sm text-muted-foreground">
                Create the master administrator account.
              </p>
            </div>

            <form onSubmit={handleSubmit} className="space-y-4">
              {serverError && (
                <div className="rounded-md bg-destructive/15 p-3 text-sm text-destructive font-medium">
                  {serverError}
                </div>
              )}

              <div className="space-y-2">
                <Label htmlFor="full_name">Full Name</Label>
                <Input
                  id="full_name"
                  name="full_name"
                  required
                  placeholder="Ram Sharma"
                  value={formData.full_name}
                  onChange={handleChange}
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="username">Username</Label>
                <Input
                  id="username"
                  name="username"
                  required
                  placeholder="admin"
                  value={formData.username}
                  onChange={handleChange}
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="email">Email Address</Label>
                <Input
                  id="email"
                  name="email"
                  type="email"
                  required
                  placeholder="example@example.com"
                  value={formData.email}
                  onChange={handleChange}
                />
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div className="space-y-2">
                  <Label htmlFor="password">Master Password</Label>
                  <Input
                    id="password"
                    name="password"
                    type="password"
                    required
                    value={formData.password}
                    onChange={handleChange}
                  />
                </div>

                <div className="space-y-2">
                  <Label htmlFor="confirm_password">Confirm Password</Label>
                  <Input
                    id="confirm_password"
                    name="confirm_password"
                    type="password"
                    required
                    value={formData.confirm_password}
                    onChange={handleChange}
                  />
                </div>
              </div>

              <Button type="submit" className="w-full mt-2" disabled={loading}>
                {loading ? (
                  <>
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    Initializing System...
                  </>
                ) : (
                  "Complete Setup"
                )}
              </Button>
            </form>
          </div>
        </div>
      </div>

      {/* Right side background image */}
      <div className="relative hidden bg-muted lg:block">
        <img
          src="/login_screen_illustration.jpg"
          alt="System Setup"
          className="absolute inset-0 h-full w-full object-cover opacity-90"
        />
        <div className="absolute inset-0 bg-gradient-to-t from-background/80 to-transparent" />
      </div>
    </div>
  );
}
