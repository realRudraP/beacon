import { useState, useEffect, useMemo } from "react";
import {
  Plus,
  Search,
  MoreHorizontal,
  Pencil,
  Trash2,
  Loader2,
  Users,
  ShieldCheck,
  UserRound,
} from "lucide-react";
import { SidebarTrigger } from "@/components/ui/sidebar";
import { UserMenu } from "./UserMenu";
import { useUserStore } from "@/store/userStore";
import { useSystemState } from "@/context/ConfigContext";
import api from "@/api/axiosClient";

// Shadcn UI Imports
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

type UserRole = "superadmin" | "admin" | "worker" | (string & {});

interface User {
  username: string;
  full_name: string;
  phone_number?: string;
  role: UserRole;
  active: boolean;
  email: string;
  weekly_off_days: number[];
}

interface UserFormData {
  full_name: string;
  username: string;
  email: string;
  role: UserRole;
  phone_number: string;
  password: string;
  confirm_password: string;
  weekly_off_days: number[];
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const ROLE_CONFIG: Record<
  string,
  {
    label: string;
    variant: "default" | "secondary" | "outline";
    icon: React.ReactNode;
  }
> = {
  superadmin: {
    label: "Super Admin",
    variant: "default",
    icon: <ShieldCheck className="h-3 w-3 mr-1" />,
  },
  admin: {
    label: "Admin",
    variant: "secondary",
    icon: <ShieldCheck className="h-3 w-3 mr-1" />,
  },
  worker: {
    label: "Worker",
    variant: "outline",
    icon: <UserRound className="h-3 w-3 mr-1" />,
  },
};

/** Fallback for any role string not in ROLE_CONFIG */
function getRoleConfig(role: string) {
  return (
    ROLE_CONFIG[role] ?? {
      label: role.charAt(0).toUpperCase() + role.slice(1),
      variant: "outline" as const,
      icon: <UserRound className="h-3 w-3 mr-1" />,
    }
  );
}

/** 0 = Sunday … 6 = Saturday, matching JS Date#getDay() / the backend's int16 day index */
const DAYS_OF_WEEK: { value: number; short: string; label: string }[] = [
  { value: 0, short: "Su", label: "Sunday" },
  { value: 1, short: "Mo", label: "Monday" },
  { value: 2, short: "Tu", label: "Tuesday" },
  { value: 3, short: "We", label: "Wednesday" },
  { value: 4, short: "Th", label: "Thursday" },
  { value: 5, short: "Fr", label: "Friday" },
  { value: 6, short: "Sa", label: "Saturday" },
];

function formatWeeklyOffDays(days: number[] | undefined): string {
  if (!days || days.length === 0) return "None";
  return days
    .slice()
    .sort((a, b) => a - b)
    .map(
      (d) => DAYS_OF_WEEK.find((x) => x.value === d)?.label.slice(0, 3) ?? "?",
    )
    .join(", ");
}

const EMPTY_FORM: UserFormData = {
  full_name: "",
  username: "",
  email: "",
  role: "worker",
  password: "",
  confirm_password: "",
  phone_number: "",
  weekly_off_days: [],
};

// ---------------------------------------------------------------------------
// Avatar helper
// ---------------------------------------------------------------------------

function UserAvatar({ name }: { name: string }) {
  const initials = name
    .split(" ")
    .slice(0, 2)
    .map((w) => w[0])
    .join("")
    .toUpperCase();

  return (
    <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-medium text-primary select-none">
      {initials}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Weekly off day picker
// ---------------------------------------------------------------------------

function WeeklyOffDayPicker({
  value,
  onToggle,
}: {
  value: number[];
  onToggle: (day: number) => void;
}) {
  return (
    <div className="space-y-2">
      <Label>Weekly Off Days</Label>
      <div className="flex flex-wrap gap-1.5">
        {DAYS_OF_WEEK.map((day) => {
          const selected = value.includes(day.value);
          return (
            <button
              key={day.value}
              type="button"
              onClick={() => onToggle(day.value)}
              aria-pressed={selected}
              title={day.label}
              className={`h-9 w-9 shrink-0 rounded-full border text-xs font-medium transition-colors ${
                selected
                  ? "border-primary bg-primary text-primary-foreground"
                  : "border-input bg-background text-muted-foreground hover:bg-muted"
              }`}
            >
              {day.short}
            </button>
          );
        })}
      </div>
      <p className="text-xs text-muted-foreground">
        Select the days this user is regularly off duty.
      </p>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Main Page
// ---------------------------------------------------------------------------

export default function UsersPage() {
  const config = useSystemState();
  const { user } = useUserStore();

  // ----- data state -----
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [fetchError, setFetchError] = useState<string | null>(null);

  // ----- filters -----
  const [search, setSearch] = useState("");
  const [roleFilter, setRoleFilter] = useState<"all" | UserRole>("all");

  // ----- add/edit dialog -----
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editingUser, setEditingUser] = useState<User | null>(null);
  const [formData, setFormData] = useState<UserFormData>(EMPTY_FORM);
  const [formError, setFormError] = useState<string | null>(null);
  const [formLoading, setFormLoading] = useState(false);

  // ----- delete dialog -----
  const [deletingUser, setDeletingUser] = useState<User | null>(null);
  const [deleteLoading, setDeleteLoading] = useState(false);

  // ----- fetch -----
  useEffect(() => {
    fetchUsers();
  }, []);

  async function fetchUsers() {
    try {
      setLoading(true);
      setFetchError(null);
      const { data } = await api.get<User[]>("/users");
      setUsers(data);
    } catch {
      setFetchError("Could not load users. Please try again.");
    } finally {
      setLoading(false);
    }
  }

  // ----- filtered list -----
  const filteredUsers = useMemo(() => {
    const q = search.toLowerCase();
    return users.filter((u) => {
      const matchesSearch =
        !q ||
        u.full_name.toLowerCase().includes(q) ||
        u.username.toLowerCase().includes(q) ||
        u.email.toLowerCase().includes(q);
      const matchesRole = roleFilter === "all" || u.role === roleFilter;
      return matchesSearch && matchesRole;
    });
  }, [users, search, roleFilter]);

  // ----- dialog helpers -----
  function openAddDialog() {
    setEditingUser(null);
    setFormData(EMPTY_FORM);
    setFormError(null);
    setDialogOpen(true);
  }

  function openEditDialog(user: User) {
    setEditingUser(user);
    setFormData({
      full_name: user.full_name,
      username: user.username,
      email: user.email,
      role: user.role,
      password: "",
      confirm_password: "",
      phone_number: user.phone_number || "",
      weekly_off_days: user.weekly_off_days ? [...user.weekly_off_days] : [],
    });
    setFormError(null);
    setDialogOpen(true);
  }

  function handleFormChange(e: React.ChangeEvent<HTMLInputElement>) {
    setFormData((prev) => ({ ...prev, [e.target.name]: e.target.value }));
  }

  function toggleWeeklyOffDay(day: number) {
    setFormData((prev) => {
      const exists = prev.weekly_off_days.includes(day);
      const weekly_off_days = exists
        ? prev.weekly_off_days.filter((d) => d !== day)
        : [...prev.weekly_off_days, day].sort((a, b) => a - b);
      return { ...prev, weekly_off_days };
    });
  }

  async function handleFormSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);

    if (!editingUser) {
      if (formData.password !== formData.confirm_password) {
        setFormError("Passwords do not match.");
        return;
      }
      if (formData.password.length < 8) {
        setFormError("Password must be at least 8 characters long.");
        return;
      }
    } else if (
      formData.password &&
      formData.password !== formData.confirm_password
    ) {
      setFormError("Passwords do not match.");
      return;
    }

    setFormLoading(true);
    try {
      if (editingUser) {
        const payload: Partial<UserFormData> = {
          full_name: formData.full_name,
          username: formData.username,
          email: formData.email,
          role: formData.role,
          phone_number: formData.phone_number,
          weekly_off_days: formData.weekly_off_days,
        };
        if (formData.password) payload.password = formData.password;
        const { data } = await api.patch<User>(
          `/users/${editingUser.username}`,
          payload,
        );
        setUsers((prev) =>
          prev.map((u) => (u.username === data.username ? data : u)),
        );
      } else {
        const { data } = await api.post<User>("/users", {
          full_name: formData.full_name,
          username: formData.username,
          email: formData.email,
          role: formData.role,
          password: formData.password,
          phone_number: formData.phone_number,
          weekly_off_days: formData.weekly_off_days,
        });
        setUsers((prev) => [data, ...prev]);
      }
      setDialogOpen(false);
    } catch (err: any) {
      setFormError(
        err.response?.data?.message ||
          "Something went wrong. Please try again.",
      );
    } finally {
      setFormLoading(false);
    }
  }

  // ----- delete helpers -----
  async function handleDelete() {
    if (!deletingUser) return;
    setDeleteLoading(true);
    try {
      await api.delete(`/users/${deletingUser.username}`);
      setUsers((prev) =>
        prev.filter((u) => u.username !== deletingUser.username),
      );
      setDeletingUser(null);
    } catch {
      // keep dialog open — surface error via toast in a real app
    } finally {
      setDeleteLoading(false);
    }
  }

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  return (

         <>
          <div className="flex items-center justify-between border-b pb-3 mb-6">
            <div className="flex items-center gap-2 sm:gap-4">
              <SidebarTrigger />
              <span className="font-semibold text-base sm:text-lg tracking-tight truncate">
                {config.branding.business_name}
              </span>
            </div>
            <UserMenu />
          </div>

          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6">
            <div className="flex items-center gap-3">
              <div className="flex h-9 w-9 items-center justify-center rounded-lg border bg-muted shrink-0">
                <Users className="h-4 w-4 text-muted-foreground" />
              </div>
              <div>
                <h1 className="text-xl font-semibold tracking-tight">Users</h1>
                <p className="text-sm text-muted-foreground">
                  Manage accounts and access levels.
                </p>
              </div>
            </div>
            <Button onClick={openAddDialog} className="w-full sm:w-auto gap-2">
              <Plus className="h-4 w-4" />
              Add User
            </Button>
          </div>


          <div className="flex flex-col sm:flex-row gap-3 mb-4">
            <div className="relative flex-1 w-full sm:max-w-sm">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground pointer-events-none" />
              <Input
                placeholder="Search users…"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                className="pl-9 w-full"
              />
            </div>

            <Select
              value={roleFilter}
              onValueChange={(v) => setRoleFilter(v as typeof roleFilter)}
            >
              <SelectTrigger className="w-full sm:w-[160px]">
                <SelectValue placeholder="All roles" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">All roles</SelectItem>
                {user?.role === "superadmin" && (
                  <SelectItem value="superadmin">Super Admin</SelectItem>
                )}
                <SelectItem value="admin">Admin</SelectItem>
                <SelectItem value="worker">Worker</SelectItem>
              </SelectContent>
            </Select>
          </div>

          {/* ── table ── */}
          <div className="rounded-lg border bg-card overflow-x-auto">
            {loading ? (
              <div className="flex flex-col items-center justify-center py-24 gap-3 text-muted-foreground min-w-[820px]">
                <Loader2 className="h-6 w-6 animate-spin" />
                <span className="text-sm">Loading users…</span>
              </div>
            ) : fetchError ? (
              <div className="flex flex-col items-center justify-center py-24 gap-3 min-w-[820px]">
                <p className="text-sm text-destructive">{fetchError}</p>
                <Button variant="outline" size="sm" onClick={fetchUsers}>
                  Retry
                </Button>
              </div>
            ) : filteredUsers.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-24 gap-2 text-muted-foreground min-w-[820px]">
                <Users className="h-8 w-8 opacity-30" />
                <p className="text-sm font-medium">No users found</p>
                <p className="text-xs">
                  {search || roleFilter !== "all"
                    ? "Try adjusting your filters."
                    : "Add your first user to get started."}
                </p>
              </div>
            ) : (
              <Table className="min-w-[820px]">
                <TableHeader>
                  <TableRow>
                    <TableHead>User</TableHead>
                    <TableHead>Username</TableHead>
                    <TableHead>Role</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>Weekly Off</TableHead>
                    <TableHead className="w-10" />
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {filteredUsers.map((user) => {
                    const role = getRoleConfig(user.role);
                    return (
                      <TableRow key={user.username}>
                        {/* name + email */}
                        <TableCell>
                          <div className="flex items-center gap-3">
                            <UserAvatar name={user.full_name} />
                            <div className="min-w-0">
                              <p className="font-medium text-sm leading-tight truncate">
                                {user.full_name}
                              </p>
                              <p className="text-xs text-muted-foreground truncate">
                                {user.email}
                              </p>
                            </div>
                          </div>
                        </TableCell>

                        {/* username */}
                        <TableCell>
                          <code className="text-xs bg-muted px-1.5 py-0.5 rounded font-mono">
                            {user.username}
                          </code>
                        </TableCell>

                        {/* role */}
                        <TableCell>
                          <Badge
                            variant={role.variant}
                            className="text-xs gap-0.5 font-normal whitespace-nowrap"
                          >
                            {role.icon}
                            {role.label}
                          </Badge>
                        </TableCell>

                        {/* status */}
                        <TableCell>
                          <div className="flex items-center gap-1.5 whitespace-nowrap">
                            <span
                              className={`h-1.5 w-1.5 rounded-full shrink-0 ${
                                user.active
                                  ? "bg-emerald-500"
                                  : "bg-muted-foreground/40"
                              }`}
                            />
                            <span className="text-sm capitalize text-muted-foreground">
                              {user.active ? "Active" : "Inactive"}
                            </span>
                          </div>
                        </TableCell>

                        {/* weekly off days */}
                        <TableCell>
                          <span className="text-sm text-muted-foreground whitespace-nowrap">
                            {formatWeeklyOffDays(user.weekly_off_days)}
                          </span>
                        </TableCell>

                        {/* actions */}
                        <TableCell>
                          <DropdownMenu>
                            <DropdownMenuTrigger asChild>
                              <Button
                                variant="ghost"
                                size="icon"
                                className="h-8 w-8 data-[state=open]:bg-muted"
                              >
                                <MoreHorizontal className="h-4 w-4" />
                                <span className="sr-only">Open menu</span>
                              </Button>
                            </DropdownMenuTrigger>
                            <DropdownMenuContent align="end" className="w-36">
                              <DropdownMenuItem
                                onClick={() => openEditDialog(user)}
                              >
                                <Pencil className="mr-2 h-4 w-4" />
                                Edit
                              </DropdownMenuItem>
                              <DropdownMenuSeparator />
                              <DropdownMenuItem
                                className="text-destructive focus:text-destructive"
                                onClick={() => setDeletingUser(user)}
                              >
                                <Trash2 className="mr-2 h-4 w-4" />
                                Delete
                              </DropdownMenuItem>
                            </DropdownMenuContent>
                          </DropdownMenu>
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            )}
          </div>

          {/* ── count footer ── */}
          {!loading && !fetchError && filteredUsers.length > 0 && (
            <p className="text-xs text-muted-foreground mt-3">
              Showing {filteredUsers.length} of {users.length}{" "}
              {users.length === 1 ? "user" : "users"}
            </p>
          )}




      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="sm:max-w-md w-[95vw] max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>
              {editingUser ? "Edit User" : "Add New User"}
            </DialogTitle>
            <DialogDescription>
              {editingUser
                ? "Update account details. Leave the password fields empty to keep the existing password."
                : "Fill in the details to create a new account."}
            </DialogDescription>
          </DialogHeader>

          <form onSubmit={handleFormSubmit} className="space-y-4 mt-2">
            {formError && (
              <div className="rounded-md bg-destructive/15 p-3 text-sm text-destructive font-medium">
                {formError}
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
                onChange={handleFormChange}
              />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="username">Username</Label>
                <Input
                  id="username"
                  name="username"
                  required
                  placeholder="ramsharma"
                  value={formData.username}
                  onChange={handleFormChange}
                  disabled={!!editingUser}
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="role">Role</Label>
                <Select
                  value={formData.role}
                  onValueChange={(v) =>
                    setFormData((prev) => ({ ...prev, role: v as UserRole }))
                  }
                >
                  <SelectTrigger id="role" className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="superadmin">Super Admin</SelectItem>
                    <SelectItem value="admin">Admin</SelectItem>
                    <SelectItem value="worker">Worker</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>

            <div className="space-y-2">
              <Label htmlFor="email">Email Address</Label>
              <Input
                id="email"
                name="email"
                type="email"
                required
                placeholder="ram@example.com"
                value={formData.email}
                onChange={handleFormChange}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="phone_number">Phone Number</Label>
              <Input
                id="phone_number"
                name="phone_number"
                type="tel"
                placeholder="98XXXXXXXX"
                value={formData.phone_number}
                onChange={handleFormChange}
              />
            </div>

            <WeeklyOffDayPicker
              value={formData.weekly_off_days}
              onToggle={toggleWeeklyOffDay}
            />

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="password">
                  {editingUser ? "New Password" : "Password"}
                </Label>
                <Input
                  id="password"
                  name="password"
                  type="password"
                  required={!editingUser}
                  placeholder={editingUser ? "Leave blank to keep" : ""}
                  value={formData.password}
                  onChange={handleFormChange}
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="confirm_password">Confirm Password</Label>
                <Input
                  id="confirm_password"
                  name="confirm_password"
                  type="password"
                  required={!editingUser}
                  placeholder={editingUser ? "Leave blank to keep" : ""}
                  value={formData.confirm_password}
                  onChange={handleFormChange}
                />
              </div>
            </div>

            <DialogFooter className="mt-6 gap-2 sm:gap-0">
              <Button
                type="button"
                variant="outline"
                className="w-full sm:w-auto"
                onClick={() => setDialogOpen(false)}
                disabled={formLoading}
              >
                Cancel
              </Button>
              <Button
                type="submit"
                className="w-full sm:w-auto"
                disabled={formLoading}
              >
                {formLoading ? (
                  <>
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    {editingUser ? "Saving…" : "Creating…"}
                  </>
                ) : editingUser ? (
                  "Save Changes"
                ) : (
                  "Create User"
                )}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* ════════════════════════════════════════════════════════
          Delete Confirmation
      ════════════════════════════════════════════════════════ */}
      <AlertDialog
        open={!!deletingUser}
        onOpenChange={(open) => !open && setDeletingUser(null)}
      >
        <AlertDialogContent className="w-[95vw] sm:w-full sm:max-w-lg">
          <AlertDialogHeader>
            <AlertDialogTitle>Delete user?</AlertDialogTitle>
            <AlertDialogDescription>
              This will permanently remove{" "}
              <span className="font-medium text-foreground">
                {deletingUser?.full_name}
              </span>{" "}
              and all associated data. This action cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter className="gap-2 sm:gap-0">
            <AlertDialogCancel
              className="w-full sm:w-auto mt-0"
              disabled={deleteLoading}
            >
              Cancel
            </AlertDialogCancel>
            <AlertDialogAction
              onClick={handleDelete}
              disabled={deleteLoading}
              className="w-full sm:w-auto bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              {deleteLoading ? (
                <>
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  Deleting…
                </>
              ) : (
                "Delete User"
              )}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      </>
  );
}
