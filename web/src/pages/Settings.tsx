import { useEffect, useState } from "react";
import {
  Loader2,
  Plus,
  Trash2,
  X,
  ShieldAlert,
  Settings2,
  BellRing,
} from "lucide-react";

import { SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar";
import { AppSidebar } from "@/components/app-sidebar";
import { UserMenu } from "./UserMenu";
import { useSystemState } from "@/context/ConfigContext";
import { useUserStore } from "@/store/userStore";
import api from "@/api/axiosClient";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

// ---------------------------------------------------------------------------
// Types — mirror the Go structs in internal/api (sync_config.go)
// ---------------------------------------------------------------------------

interface Geofence {
  id: string;
  name: string;
  center_lat: number;
  center_lng: number;
  radius: number;
}

interface NotificationConditions {
  is_working_day?: boolean | null;
  is_public_holiday?: boolean | null;
  has_clocked_in_today?: boolean | null;
  has_clocked_out_today?: boolean | null;
  has_pending_sync_records?: boolean | null;
}

interface NotificationRule {
  id: string;
  title: string;
  description: string;
  time: string;
  enabled: boolean;
  conditions: NotificationConditions;
}

interface GlobalPolicies {
  allowed_accuracy_meters: number;
  public_holidays: string[];
  geofences: Geofence[];
  notification_rules: NotificationRule[];
}

type Banner = { type: "success" | "error"; message: string };

const CONDITION_FIELDS: { key: keyof NotificationConditions; label: string }[] =
  [
    { key: "is_working_day", label: "Today is a working day" },
    { key: "is_public_holiday", label: "Today is a public holiday" },
    { key: "has_clocked_in_today", label: "Clocked in today" },
    { key: "has_clocked_out_today", label: "Clocked out today" },
    { key: "has_pending_sync_records", label: "Has pending sync records" },
  ];

function conditionToValue(value?: boolean | null): string {
  if (value === true) return "true";
  if (value === false) return "false";
  return "any";
}

function valueToCondition(value: string): boolean | undefined {
  if (value === "true") return true;
  if (value === "false") return false;
  return undefined;
}

function formatDate(value: string) {
  const d = new Date(`${value}T00:00:00`);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}

function normalizePolicies(data: Partial<GlobalPolicies>): GlobalPolicies {
  return {
    allowed_accuracy_meters: data.allowed_accuracy_meters ?? 0,
    public_holidays: data.public_holidays ?? [],
    geofences: data.geofences ?? [],
    notification_rules: data.notification_rules ?? [],
  };
}

function canManagePolicies(role?: string | null) {
  if (!role) return false;
  const normalized = role.toLowerCase();
  return normalized.includes("admin") || normalized.includes("super");
}

export default function SettingsPage() {
  const { branding } = useSystemState();
  const { user, fetchUser } = useUserStore();
  const canManage = canManagePolicies(user?.role);

  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [banner, setBanner] = useState<Banner | null>(null);

  const [original, setOriginal] = useState<GlobalPolicies | null>(null);
  const [draft, setDraft] = useState<GlobalPolicies | null>(null);

  const [savePending, setSavePending] = useState(false);
  const [newHoliday, setNewHoliday] = useState("");

  useEffect(() => {
    fetchUser();

    let cancelled = false;

    (async () => {
      try {
        const { data } = await api.get("/config");
        if (cancelled) return;
        const normalized = normalizePolicies(data);
        setOriginal(normalized);
        setDraft(normalized);
      } catch {
        if (!cancelled) setLoadError(true);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  async function savePolicies() {
    if (!draft) return;
    setBanner(null);
    setSavePending(true);
    try {
      const payload = {
        allowed_accuracy_meters: draft.allowed_accuracy_meters,
        public_holidays: draft.public_holidays,
        notification_rules: draft.notification_rules,
      };
      const { data } = await api.put("/config", payload);
      const updated: GlobalPolicies = {
        ...normalizePolicies(data),
        geofences: draft.geofences,
      };
      setOriginal(updated);
      setDraft(updated);
      setBanner({ type: "success", message: "Settings saved." });
    } catch (err: any) {
      setBanner({
        type: "error",
        message: err.response?.data?.message || "Failed to save settings.",
      });
    } finally {
      setSavePending(false);
    }
  }

  function discardChanges() {
    if (original) setDraft(original);
    setBanner(null);
  }

  function addHoliday() {
    if (!draft || !newHoliday) return;
    if (draft.public_holidays.includes(newHoliday)) {
      setNewHoliday("");
      return;
    }
    setDraft({
      ...draft,
      public_holidays: [...draft.public_holidays, newHoliday].sort(),
    });
    setNewHoliday("");
  }

  function removeHoliday(date: string) {
    if (!draft) return;
    setDraft({
      ...draft,
      public_holidays: draft.public_holidays.filter((d) => d !== date),
    });
  }

  function updateRule(index: number, patch: Partial<NotificationRule>) {
    if (!draft) return;
    const rules = [...draft.notification_rules];
    rules[index] = { ...rules[index], ...patch };
    setDraft({ ...draft, notification_rules: rules });
  }

  function updateRuleCondition(
    index: number,
    key: keyof NotificationConditions,
    value: string,
  ) {
    if (!draft) return;
    const conditions = { ...draft.notification_rules[index].conditions };
    const next = valueToCondition(value);
    if (next === undefined) {
      delete conditions[key];
    } else {
      conditions[key] = next;
    }
    updateRule(index, { conditions });
  }

  function addRule() {
    if (!draft) return;
    const rule: NotificationRule = {
      id: crypto.randomUUID(),
      title: "New rule",
      description: "",
      time: "09:00",
      enabled: true,
      conditions: {},
    };
    setDraft({
      ...draft,
      notification_rules: [...draft.notification_rules, rule],
    });
  }

  function removeRule(index: number) {
    if (!draft) return;
    setDraft({
      ...draft,
      notification_rules: draft.notification_rules.filter(
        (_, i) => i !== index,
      ),
    });
  }

  return (
    <SidebarProvider>
      <div className="flex min-h-screen w-full">
        <AppSidebar />
        <main className="flex-1 p-6">
          <div className="flex items-center justify-between border-b pb-3 mb-4">
            <div className="flex items-center gap-4">
              <SidebarTrigger />
              <span className="font-semibold text-lg tracking-tight">
                {branding.business_name}
              </span>
            </div>
            <UserMenu />
          </div>

          <div className="mx-auto w-full max-w-4xl space-y-6">
            <div className="space-y-1">
              <h1 className="text-2xl font-bold tracking-tight">Settings</h1>
              <p className="text-sm text-muted-foreground">
                Manage attendance accuracy, geofences, and notification rules.
              </p>
            </div>

            {banner && (
              <div
                className={`rounded-md p-3 text-sm font-medium ${
                  banner.type === "success"
                    ? "bg-emerald-500/15 text-emerald-600"
                    : "bg-destructive/15 text-destructive"
                }`}
              >
                {banner.message}
              </div>
            )}

            {!canManage && (
              <div className="flex items-center gap-2 rounded-md border bg-muted/40 p-3 text-sm text-muted-foreground">
                <ShieldAlert className="h-4 w-4 shrink-0" />
                You're viewing these settings in read-only mode. Ask an
                administrator to make changes.
              </div>
            )}

            {loading ? (
              <div className="flex items-center justify-center gap-2 py-24 text-sm text-muted-foreground">
                <Loader2 className="h-5 w-5 animate-spin" />
                Loading settings...
              </div>
            ) : loadError || !draft ? (
              <div className="rounded-md border border-destructive/30 bg-destructive/10 p-4 text-sm text-destructive">
                Couldn't load settings. Refresh the page to try again.
              </div>
            ) : (
              <Tabs defaultValue="general" className="w-full">
                <TabsList>
                  <TabsTrigger value="general">
                    <Settings2 className="mr-2 h-4 w-4" />
                    General
                  </TabsTrigger>

                  <TabsTrigger value="notifications">
                    <BellRing className="mr-2 h-4 w-4" />
                    Notifications
                  </TabsTrigger>
                </TabsList>

                {/* ---------------------------- General ---------------------------- */}
                <TabsContent value="general" className="mt-4 space-y-6">
                  <Card>
                    <CardHeader>
                      <CardTitle>Attendance accuracy</CardTitle>
                      <CardDescription>
                        The maximum GPS accuracy radius allowed when clocking in
                        or out.
                      </CardDescription>
                    </CardHeader>
                    <CardContent className="max-w-xs space-y-2">
                      <Label htmlFor="accuracy">
                        Allowed accuracy (meters)
                      </Label>
                      <Input
                        id="accuracy"
                        type="number"
                        min={1}
                        value={draft.allowed_accuracy_meters}
                        disabled={!canManage}
                        onChange={(e) =>
                          setDraft((d) =>
                            d
                              ? {
                                  ...d,
                                  allowed_accuracy_meters: Number(
                                    e.target.value,
                                  ),
                                }
                              : d,
                          )
                        }
                      />
                      <p className="text-xs text-muted-foreground">
                        Clock-ins recorded with a GPS accuracy worse than this
                        value are flagged for review.
                      </p>
                    </CardContent>
                  </Card>

                  <Card>
                    <CardHeader>
                      <CardTitle>Public holidays</CardTitle>
                      <CardDescription>
                        Dates treated as public holidays for the whole
                        organization.
                      </CardDescription>
                    </CardHeader>
                    <CardContent className="space-y-3">
                      {canManage && (
                        <div className="flex gap-2">
                          <Input
                            type="date"
                            value={newHoliday}
                            onChange={(e) => setNewHoliday(e.target.value)}
                            className="max-w-[200px]"
                          />
                          <Button
                            type="button"
                            variant="outline"
                            onClick={addHoliday}
                            disabled={!newHoliday}
                          >
                            <Plus className="mr-1 h-4 w-4" />
                            Add date
                          </Button>
                        </div>
                      )}
                      {draft.public_holidays.length === 0 ? (
                        <p className="text-sm text-muted-foreground">
                          No public holidays configured.
                        </p>
                      ) : (
                        <div className="flex flex-wrap gap-2">
                          {draft.public_holidays.map((date) => (
                            <Badge
                              key={date}
                              variant="secondary"
                              className="gap-1 py-1.5"
                            >
                              {formatDate(date)}
                              {canManage && (
                                <button
                                  type="button"
                                  onClick={() => removeHoliday(date)}
                                  aria-label={`Remove ${date}`}
                                  className="ml-1 rounded-full hover:bg-background/60"
                                >
                                  <X className="h-3 w-3" />
                                </button>
                              )}
                            </Badge>
                          ))}
                        </div>
                      )}
                    </CardContent>
                  </Card>

                  {canManage && (
                    <div className="flex items-center gap-3">
                      <Button onClick={savePolicies} disabled={savePending}>
                        {savePending && (
                          <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                        )}
                        Save changes
                      </Button>
                      <Button
                        variant="ghost"
                        onClick={discardChanges}
                        disabled={savePending}
                      >
                        Discard changes
                      </Button>
                    </div>
                  )}
                </TabsContent>

                {/* ------------------------- Notifications -------------------------- */}
                <TabsContent value="notifications" className="mt-4 space-y-4">
                  {draft.notification_rules.length === 0 && (
                    <p className="text-sm text-muted-foreground">
                      No notification rules configured yet.
                    </p>
                  )}

                  {draft.notification_rules.map((rule, idx) => (
                    <Card key={rule.id}>
                      <CardHeader className="flex flex-row items-start justify-between gap-4">
                        <div className="flex-1 space-y-2">
                          <Label htmlFor={`rule-title-${rule.id}`}>Title</Label>
                          <Input
                            id={`rule-title-${rule.id}`}
                            value={rule.title}
                            disabled={!canManage}
                            onChange={(e) =>
                              updateRule(idx, { title: e.target.value })
                            }
                          />
                        </div>
                        <div className="flex items-center gap-2 pt-7">
                          <Switch
                            checked={rule.enabled}
                            disabled={!canManage}
                            onCheckedChange={(checked) =>
                              updateRule(idx, { enabled: checked })
                            }
                          />
                          {canManage && (
                            <Button
                              type="button"
                              variant="ghost"
                              size="icon"
                              onClick={() => removeRule(idx)}
                            >
                              <Trash2 className="h-4 w-4 text-destructive" />
                            </Button>
                          )}
                        </div>
                      </CardHeader>
                      <CardContent className="space-y-6">
                        <div className="grid gap-4 sm:grid-cols-2">
                          <div className="space-y-2">
                            <Label htmlFor={`rule-desc-${rule.id}`}>
                              Description
                            </Label>
                            <Input
                              id={`rule-desc-${rule.id}`}
                              value={rule.description}
                              disabled={!canManage}
                              onChange={(e) =>
                                updateRule(idx, { description: e.target.value })
                              }
                            />
                          </div>
                          <div className="space-y-2">
                            <Label htmlFor={`rule-time-${rule.id}`}>
                              Trigger Time
                            </Label>
                            <Input
                              id={`rule-time-${rule.id}`}
                              type="time"
                              value={rule.time || ""}
                              disabled={!canManage}
                              onChange={(e) =>
                                updateRule(idx, { time: e.target.value })
                              }
                            />
                          </div>
                        </div>

                        <div>
                          <Label className="text-xs uppercase tracking-wide text-muted-foreground">
                            Trigger when
                          </Label>
                          <div className="mt-2 grid gap-3 sm:grid-cols-2">
                            {CONDITION_FIELDS.map(({ key, label }) => (
                              <div key={key} className="space-y-1">
                                <Label className="text-xs text-muted-foreground">
                                  {label}
                                </Label>
                                <Select
                                  disabled={!canManage}
                                  value={conditionToValue(rule.conditions[key])}
                                  onValueChange={(v) =>
                                    updateRuleCondition(idx, key, v)
                                  }
                                >
                                  <SelectTrigger className="h-8">
                                    <SelectValue />
                                  </SelectTrigger>
                                  <SelectContent>
                                    <SelectItem value="any">
                                      Doesn't matter
                                    </SelectItem>
                                    <SelectItem value="true">
                                      Must be true
                                    </SelectItem>
                                    <SelectItem value="false">
                                      Must be false
                                    </SelectItem>
                                  </SelectContent>
                                </Select>
                              </div>
                            ))}
                          </div>
                        </div>
                      </CardContent>
                    </Card>
                  ))}

                  {canManage && (
                    <div className="flex items-center gap-3">
                      <Button type="button" variant="outline" onClick={addRule}>
                        <Plus className="mr-1 h-4 w-4" />
                        Add rule
                      </Button>
                      <Button onClick={savePolicies} disabled={savePending}>
                        {savePending && (
                          <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                        )}
                        Save changes
                      </Button>
                      <Button
                        variant="ghost"
                        onClick={discardChanges}
                        disabled={savePending}
                      >
                        Discard changes
                      </Button>
                    </div>
                  )}
                </TabsContent>
              </Tabs>
            )}
          </div>
        </main>
      </div>
    </SidebarProvider>
  );
}
