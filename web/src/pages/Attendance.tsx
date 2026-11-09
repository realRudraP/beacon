import { useEffect, useMemo, useState } from "react";
import {
  Loader2,
  CalendarClock,
  CalendarDays,
  UserX,
  Clock,
  ListChecks,
  ChevronLeft,
  ChevronRight,
  ImageOff,
  X,
  MapPin,
} from "lucide-react";


import { UserMenu } from "./UserMenu";
import { useSystemState } from "@/context/ConfigContext";
import api from "@/api/axiosClient";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { Calendar } from "@/components/ui/calendar";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
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

// Leaflet (used by the Location Checks map)
import {
  MapContainer,
  TileLayer,
  Circle,
  CircleMarker,
  useMap,
} from "react-leaflet";
import "leaflet/dist/leaflet.css";

// ---------------------------------------------------------------------------
// Types — mirror the Go response DTOs in internal/api (attendance_admin.go)
// ---------------------------------------------------------------------------

interface AttendanceRecordDTO {
  id: string;
  user_id: string;
  date: string; // DD-MM-YYYY
  check_in?: string; // RFC3339
  check_in_photo_uri?: string;
  check_out?: string; // RFC3339
  check_out_photo_uri?: string;
  location_checks_count?: number;
  location_checks_within?: number;
}

interface AttendanceWithUserDTO extends AttendanceRecordDTO {
  full_name: string;
  email: string;
}

interface LocationCheckDTO {
  id: string;
  attendance_record_id: string;
  user_id: string;
  full_name: string;
  email: string;
  captured_at: string;
  latitude: number;
  longitude: number;
  accuracy?: number;
  sequence_number: number;
  within_geofence: boolean;
  nearest_geofence_id?: string;
  nearest_geofence_name?: string;
  distance_meters?: number;
}

interface GeofenceOption {
  id: string;
  name: string;
  center_lat: number;
  center_lng: number;
  radius: number;
}

interface AbsentUser {
  id: string;
  full_name: string;
  email: string;
}

/** Assumes /users now returns `id` per the backend patch. */
interface UserOption {
  id: string;
  full_name: string;
  username: string;
}

const PAGE_SIZE = 20;
const LOCATION_PAGE_SIZE = 25;

// ---------------------------------------------------------------------------
// Date helpers — the backend expects and returns DD-MM-YYYY on the wire
// ---------------------------------------------------------------------------

function toIsoDate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

/** yyyy-mm-dd -> DD-MM-YYYY (for query params) */
function isoToApiDate(iso: string): string {
  const [y, m, d] = iso.split("-");
  return `${d}-${m}-${y}`;
}

/** Date -> DD-MM-YYYY, matching the key format the backend returns in `date`. */
function toApiDateKey(d: Date): string {
  const day = String(d.getDate()).padStart(2, "0");
  const month = String(d.getMonth() + 1).padStart(2, "0");
  return `${day}-${month}-${d.getFullYear()}`;
}

function parseApiDate(value: string): Date | null {
  const [d, m, y] = value.split("-").map(Number);
  if (!d || !m || !y) return null;
  const date = new Date(y, m - 1, d);
  return Number.isNaN(date.getTime()) ? null : date;
}

function formatDate(value?: string): string {
  if (!value) return "—";
  const date = parseApiDate(value);
  if (!date) return value;
  return date.toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}

function formatTime(value?: string): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleTimeString(undefined, {
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatDateTime(value?: string): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatDuration(checkIn?: string, checkOut?: string): string {
  if (!checkIn || !checkOut) return "—";
  const start = new Date(checkIn).getTime();
  const end = new Date(checkOut).getTime();
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return "—";
  const totalMinutes = Math.round((end - start) / 60000);
  const h = Math.floor(totalMinutes / 60);
  const m = totalMinutes % 60;
  return `${h}h ${m}m`;
}

// ---------------------------------------------------------------------------
// Photos — authenticated fetch, since /static/photos/* requires a bearer token
// that a plain <img src> tag can't send.
// ---------------------------------------------------------------------------

/**
 * ASSUMPTION: check_in_photo_uri / check_out_photo_uri store whatever should be
 * appended directly after "/static/photos/". If your upload handler stores
 * something else (e.g. an absolute path, or a value that already includes
 * "photos/"), adjust this function to match.
 */
function resolvePhotoUrl(uri?: string): string | null {
  if (!uri) return null;
  if (/^https?:\/\//i.test(uri)) return uri;
  return `/${uri.replace(/^\/+/, "")}`;
}

function AuthenticatedImage({
  uri,
  alt,
  className,
}: {
  uri?: string;
  alt: string;
  className?: string;
}) {
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [state, setState] = useState<"loading" | "ready" | "error" | "empty">(
    uri ? "loading" : "empty",
  );

  useEffect(() => {
    if (!uri) {
      setState("empty");
      return;
    }
    const url = resolvePhotoUrl(uri);
    if (!url) {
      setState("error");
      return;
    }

    let currentObjectUrl: string | null = null;
    let cancelled = false;
    setState("loading");

    api
      .get(url, { responseType: "blob", baseURL: "" })
      .then((res) => {
        if (cancelled) return;
        currentObjectUrl = URL.createObjectURL(res.data);
        setObjectUrl(currentObjectUrl);
        setState("ready");
      })
      .catch(() => {
        if (!cancelled) setState("error");
      });

    return () => {
      cancelled = true;
      if (currentObjectUrl) URL.revokeObjectURL(currentObjectUrl);
    };
  }, [uri]);

  if (state === "empty") {
    return (
      <div
        className={`flex items-center justify-center bg-muted text-muted-foreground ${className ?? ""}`}
      >
        <ImageOff className="h-3.5 w-3.5" />
      </div>
    );
  }
  if (state === "loading") {
    return (
      <div
        className={`flex items-center justify-center bg-muted ${className ?? ""}`}
      >
        <Loader2 className="h-3.5 w-3.5 animate-spin text-muted-foreground" />
      </div>
    );
  }
  if (state === "error") {
    return (
      <div
        className={`flex items-center justify-center bg-destructive/10 text-destructive ${className ?? ""}`}
      >
        <ImageOff className="h-3.5 w-3.5" />
      </div>
    );
  }
  return (
    <img
      src={objectUrl ?? undefined}
      alt={alt}
      className={`object-cover ${className ?? ""}`}
    />
  );
}

function PhotoThumbnail({ uri, label }: { uri?: string; label: string }) {
  const [open, setOpen] = useState(false);

  if (!uri) {
    return <span className="text-xs text-muted-foreground">—</span>;
  }

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        title={`View ${label.toLowerCase()}`}
        className="block h-10 w-10 shrink-0 overflow-hidden rounded-md border hover:ring-2 hover:ring-primary/40 transition-shadow"
      >
        <AuthenticatedImage uri={uri} alt={label} className="h-10 w-10" />
      </button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="sm:max-w-lg w-[92vw]">
          <DialogHeader>
            <DialogTitle>{label}</DialogTitle>
          </DialogHeader>
          <div className="overflow-hidden rounded-md border">
            <AuthenticatedImage
              uri={uri}
              alt={label}
              className="w-full h-auto max-h-[70vh]"
            />
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}

// ---------------------------------------------------------------------------
// Small shared UI bits
// ---------------------------------------------------------------------------

function EmployeeCell({ name, email }: { name: string; email?: string }) {
  const initials = name
    .split(" ")
    .slice(0, 2)
    .map((w) => w[0])
    .join("")
    .toUpperCase();

  return (
    <div className="flex items-center gap-3">
      <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-medium text-primary select-none">
        {initials || "?"}
      </div>
      <div className="min-w-0">
        <p className="font-medium text-sm leading-tight truncate">{name}</p>
        {email && (
          <p className="text-xs text-muted-foreground truncate">{email}</p>
        )}
      </div>
    </div>
  );
}

function ShiftStatusBadge({
  checkIn,
  checkOut,
  stale,
}: {
  checkIn?: string;
  checkOut?: string;
  stale?: boolean;
}) {
  if (!checkIn) return <Badge variant="outline">No record</Badge>;
  if (stale) {
    return (
      <Badge variant="destructive" className="gap-1 font-normal">
        Never closed
      </Badge>
    );
  }
  if (!checkOut) {
    return (
      <Badge className="gap-1 bg-emerald-500/15 text-emerald-600 hover:bg-emerald-500/15 font-normal">
        Clocked in
      </Badge>
    );
  }
  return (
    <Badge variant="secondary" className="font-normal">
      Complete
    </Badge>
  );
}

function LocationChecksBadge({
  count,
  within,
}: {
  count?: number;
  within?: number;
}) {
  if (count === undefined) {
    return <span className="text-xs text-muted-foreground">—</span>;
  }
  if (count === 0) {
    return (
      <Badge variant="outline" className="gap-1 font-normal text-muted-foreground">
        <MapPin className="h-3 w-3" />
        0
      </Badge>
    );
  }
  const inside = within ?? 0;
  const allInside = inside === count;
  return (
    <Badge
      variant="secondary"
      title={`${inside} of ${count} location check${count === 1 ? "" : "s"} inside a geofence`}
      className={
        allInside
          ? "gap-1 bg-emerald-500/15 text-emerald-600 hover:bg-emerald-500/15 font-normal"
          : "gap-1 bg-amber-500/15 text-amber-700 hover:bg-amber-500/15 font-normal"
      }
    >
      <MapPin className="h-3 w-3" />
      {inside}/{count} passed
    </Badge>
  );
}

function FitMapToChecks({ points }: { points: [number, number][] }) {
  const map = useMap();
  const key = points.map((p) => p.join(",")).join(";");

  useEffect(() => {
    if (points.length === 0) return;
    if (points.length === 1) {
      map.setView(points[0], 16);
    } else {
      map.fitBounds(points, { padding: [32, 32], maxZoom: 16 });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map, key]);

  return null;
}

function formatCoords(lat: number, lng: number): string {
  return `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
}

function EmptyState({
  icon,
  title,
  description,
}: {
  icon: React.ReactNode;
  title: string;
  description: string;
}) {
  return (
    <div className="flex flex-col items-center justify-center py-20 gap-2 text-muted-foreground min-w-[760px]">
      {icon}
      <p className="text-sm font-medium">{title}</p>
      <p className="text-xs">{description}</p>
    </div>
  );
}

function TableSkeletonState({ label }: { label: string }) {
  return (
    <div className="flex flex-col items-center justify-center py-20 gap-3 text-muted-foreground min-w-[760px]">
      <Loader2 className="h-6 w-6 animate-spin" />
      <span className="text-sm">{label}</span>
    </div>
  );
}

function ErrorState({
  message,
  onRetry,
}: {
  message: string;
  onRetry: () => void;
}) {
  return (
    <div className="flex flex-col items-center justify-center py-20 gap-3 min-w-[760px]">
      <p className="text-sm text-destructive">{message}</p>
      <Button variant="outline" size="sm" onClick={onRetry}>
        Retry
      </Button>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Main Page
// ---------------------------------------------------------------------------

export default function AttendancePage() {
  const { branding } = useSystemState();

  const [users, setUsers] = useState<UserOption[]>([]);

  // ----- All Records tab -----
  const [userFilter, setUserFilter] = useState<string>("all");
  const [fromDate, setFromDate] = useState("");
  const [toDate, setToDate] = useState("");
  const [page, setPage] = useState(0);
  const [records, setRecords] = useState<
    AttendanceWithUserDTO[] | AttendanceRecordDTO[]
  >([]);
  const [total, setTotal] = useState(0);
  const [recordsLoading, setRecordsLoading] = useState(true);
  const [recordsError, setRecordsError] = useState<string | null>(null);

  // ----- Currently In tab -----
  const [openDate, setOpenDate] = useState("");
  const [openShifts, setOpenShifts] = useState<AttendanceWithUserDTO[]>([]);
  const [openLoading, setOpenLoading] = useState(true);
  const [openError, setOpenError] = useState<string | null>(null);

  // ----- Absent Today tab -----
  const [absentDate, setAbsentDate] = useState("");
  const [absentUsers, setAbsentUsers] = useState<AbsentUser[]>([]);
  const [absentLoading, setAbsentLoading] = useState(true);
  const [absentError, setAbsentError] = useState<string | null>(null);

  // ----- Stale Shifts tab -----
  const [staleShifts, setStaleShifts] = useState<AttendanceWithUserDTO[]>([]);
  const [staleLoading, setStaleLoading] = useState(true);
  const [staleError, setStaleError] = useState<string | null>(null);

  // ----- Location Checks tab -----
  const [locationUserFilter, setLocationUserFilter] = useState<string>("all");
  const [locationFromDate, setLocationFromDate] = useState("");
  const [locationToDate, setLocationToDate] = useState("");
  const [locationPage, setLocationPage] = useState(0);
  const [locationChecks, setLocationChecks] = useState<LocationCheckDTO[]>([]);
  const [locationTotal, setLocationTotal] = useState(0);
  const [locationLoading, setLocationLoading] = useState(true);
  const [locationError, setLocationError] = useState<string | null>(null);
  const [geofences, setGeofences] = useState<GeofenceOption[]>([]);

  // ----- Calendar tab -----
  const [calendarUserId, setCalendarUserId] = useState<string>("");
  const [calendarMonth, setCalendarMonth] = useState<Date>(new Date());
  const [calendarRecords, setCalendarRecords] = useState<AttendanceRecordDTO[]>(
    [],
  );
  const [calendarLoading, setCalendarLoading] = useState(false);
  const [calendarError, setCalendarError] = useState<string | null>(null);
  const [selectedDay, setSelectedDay] = useState<Date | undefined>(undefined);

  // ----- fetch: user list -----
  useEffect(() => {
    api
      .get<UserOption[]>("/users")
      .then(({ data }) => setUsers(data))
      .catch(() => {
        // Non-fatal — filter dropdowns just won't populate.
      });
  }, []);

  // ----- fetch: All Records -----
  async function fetchRecords() {
    setRecordsLoading(true);
    setRecordsError(null);
    try {
      const params: Record<string, string | number> = {
        limit: PAGE_SIZE,
        offset: page * PAGE_SIZE,
      };
      if (userFilter !== "all") params.user_id = userFilter;
      if (fromDate) params.from = isoToApiDate(fromDate);
      if (toDate) params.to = isoToApiDate(toDate);

      const { data } = await api.get("/attendance/admin", { params });
      setRecords(data.records ?? []);
      setTotal(data.total ?? 0);
    } catch {
      setRecordsError("Could not load attendance records. Please try again.");
    } finally {
      setRecordsLoading(false);
    }
  }

  useEffect(() => {
    fetchRecords();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userFilter, fromDate, toDate, page]);

  // ----- fetch: Currently In -----
  async function fetchOpenShifts() {
    setOpenLoading(true);
    setOpenError(null);
    try {
      const params: Record<string, string> = {};
      if (openDate) params.date = isoToApiDate(openDate);
      const { data } = await api.get<AttendanceWithUserDTO[]>(
        "/attendance/admin/open",
        { params },
      );
      setOpenShifts(data ?? []);
    } catch {
      setOpenError("Could not load open shifts. Please try again.");
    } finally {
      setOpenLoading(false);
    }
  }

  useEffect(() => {
    fetchOpenShifts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openDate]);

  // ----- fetch: Absent Today -----
  async function fetchAbsentUsers() {
    setAbsentLoading(true);
    setAbsentError(null);
    try {
      const params: Record<string, string> = {};
      if (absentDate) params.date = isoToApiDate(absentDate);
      const { data } = await api.get<AbsentUser[]>("/attendance/admin/absent", {
        params,
      });
      setAbsentUsers(data ?? []);
    } catch {
      setAbsentError("Could not load absentees. Please try again.");
    } finally {
      setAbsentLoading(false);
    }
  }

  useEffect(() => {
    fetchAbsentUsers();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [absentDate]);

  // ----- fetch: Stale Shifts -----
  async function fetchStaleShifts() {
    setStaleLoading(true);
    setStaleError(null);
    try {
      const { data } = await api.get<AttendanceWithUserDTO[]>(
        "/attendance/admin/stale",
      );
      setStaleShifts(data ?? []);
    } catch {
      setStaleError("Could not load stale shifts. Please try again.");
    } finally {
      setStaleLoading(false);
    }
  }

  useEffect(() => {
    fetchStaleShifts();
  }, []);

  // ----- fetch: Location Checks -----
  async function fetchLocationChecks() {
    setLocationLoading(true);
    setLocationError(null);
    try {
      const params: Record<string, string | number> = {
        limit: LOCATION_PAGE_SIZE,
        offset: locationPage * LOCATION_PAGE_SIZE,
      };
      if (locationUserFilter !== "all") params.user_id = locationUserFilter;
      if (locationFromDate) params.from = isoToApiDate(locationFromDate);
      if (locationToDate) params.to = isoToApiDate(locationToDate);

      const { data } = await api.get<{
        checks: LocationCheckDTO[];
        total: number;
      }>("/attendance/admin/location-checks", { params });
      setLocationChecks(data.checks ?? []);
      setLocationTotal(data.total ?? 0);
    } catch {
      setLocationError("Could not load location checks. Please try again.");
    } finally {
      setLocationLoading(false);
    }
  }

  useEffect(() => {
    fetchLocationChecks();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [locationUserFilter, locationFromDate, locationToDate, locationPage]);

  useEffect(() => {
    api
      .get<GeofenceOption[]>("/geofences")
      .then(({ data }) => setGeofences(data ?? []))
      .catch(() => {
        // Non-fatal — the map just won't show geofence circles.
      });
  }, []);

  // ----- fetch: Calendar (per-employee, per-month) -----
  useEffect(() => {
    if (!calendarUserId) {
      setCalendarRecords([]);
      return;
    }
    const year = calendarMonth.getFullYear();
    const month = calendarMonth.getMonth();
    const firstDay = new Date(year, month, 1);
    const lastDay = new Date(year, month + 1, 0);

    let cancelled = false;
    setCalendarLoading(true);
    setCalendarError(null);

    api
      .get("/attendance/admin", {
        params: {
          user_id: calendarUserId,
          from: isoToApiDate(toIsoDate(firstDay)),
          to: isoToApiDate(toIsoDate(lastDay)),
          limit: 31,
          offset: 0,
        },
      })
      .then(({ data }) => {
        if (!cancelled) setCalendarRecords(data.records ?? []);
      })
      .catch(() => {
        if (!cancelled)
          setCalendarError("Could not load the calendar for this employee.");
      })
      .finally(() => {
        if (!cancelled) setCalendarLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [calendarUserId, calendarMonth]);

  const showingUserColumn = userFilter === "all";
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const locationTotalPages = Math.max(
    1,
    Math.ceil(locationTotal / LOCATION_PAGE_SIZE),
  );

  const locationMapPoints = useMemo<[number, number][]>(
    () => locationChecks.map((c) => [c.latitude, c.longitude]),
    [locationChecks],
  );

  const locationMapCenter = useMemo<[number, number]>(() => {
    if (locationMapPoints.length > 0) return locationMapPoints[0];
    if (geofences.length > 0) {
      return [geofences[0].center_lat, geofences[0].center_lng];
    }
    return [28.6139, 77.209];
  }, [locationMapPoints, geofences]);

  const selectedUserName = useMemo(
    () => users.find((u) => u.id === userFilter)?.full_name,
    [users, userFilter],
  );

  function resetFilters() {
    setUserFilter("all");
    setFromDate("");
    setToDate("");
    setPage(0);
  }

  function resetLocationFilters() {
    setLocationUserFilter("all");
    setLocationFromDate("");
    setLocationToDate("");
    setLocationPage(0);
  }

  const hasActiveFilters = userFilter !== "all" || !!fromDate || !!toDate;

  // ----- calendar day classification -----
  const recordsByDate = useMemo(() => {
    const map = new Map<string, AttendanceRecordDTO>();
    calendarRecords.forEach((r) => map.set(r.date, r));
    return map;
  }, [calendarRecords]);

  const { completeDays, openDays, missingDays } = useMemo(() => {
    const complete: Date[] = [];
    const open: Date[] = [];
    const missing: Date[] = [];

    const today = new Date();
    today.setHours(0, 0, 0, 0);

    const daysInMonth = new Date(
      calendarMonth.getFullYear(),
      calendarMonth.getMonth() + 1,
      0,
    ).getDate();

    for (let day = 1; day <= daysInMonth; day++) {
      const d = new Date(
        calendarMonth.getFullYear(),
        calendarMonth.getMonth(),
        day,
      );
      const rec = recordsByDate.get(toApiDateKey(d));
      if (rec?.check_in && rec.check_out) complete.push(d);
      else if (rec?.check_in) open.push(d);
      else if (d < today) missing.push(d);
    }

    return { completeDays: complete, openDays: open, missingDays: missing };
  }, [calendarMonth, recordsByDate]);

  const selectedDayRecord = selectedDay
    ? recordsByDate.get(toApiDateKey(selectedDay))
    : undefined;

  return (

      <div className="flex min-h-screen w-full">

        <main className="flex-1 p-4 sm:p-6 min-w-0">
          <div className="flex items-center justify-between border-b pb-3 mb-6">
            <div className="flex items-center gap-2 sm:gap-4">

              <span className="font-semibold text-base sm:text-lg tracking-tight truncate">
                {branding.business_name}
              </span>
            </div>
            <UserMenu />
          </div>

          <div className="flex items-center gap-3 mb-6">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg border bg-muted shrink-0">
              <CalendarClock className="h-4 w-4 text-muted-foreground" />
            </div>
            <div>
              <h1 className="text-xl font-semibold tracking-tight">
                Attendance
              </h1>
              <p className="text-sm text-muted-foreground">
                Review clock-ins, open shifts, and absences across the team.
              </p>
            </div>
          </div>

          <Tabs defaultValue="records" className="w-full">
            <TabsList className="h-auto flex-wrap">
              <TabsTrigger value="records">
                <ListChecks className="mr-2 h-4 w-4" />
                All Records
              </TabsTrigger>
              <TabsTrigger value="location">
                <MapPin className="mr-2 h-4 w-4" />
                Location Checks
              </TabsTrigger>
              <TabsTrigger value="calendar">
                <CalendarDays className="mr-2 h-4 w-4" />
                Calendar
              </TabsTrigger>
              <TabsTrigger value="open">
                <Clock className="mr-2 h-4 w-4" />
                Currently In
              </TabsTrigger>
              <TabsTrigger value="absent">
                <UserX className="mr-2 h-4 w-4" />
                Absent
              </TabsTrigger>
              <TabsTrigger value="stale">
                <CalendarClock className="mr-2 h-4 w-4" />
                Stale Shifts
              </TabsTrigger>
            </TabsList>

            {/* ------------------------------- All Records ------------------------------- */}
            <TabsContent value="records" className="mt-4 space-y-4">
              <Card>
                <CardContent className="pt-6">
                  <div className="flex flex-col sm:flex-row gap-3 sm:items-end">
                    <div className="space-y-1.5 flex-1 sm:max-w-[220px]">
                      <Label className="text-xs text-muted-foreground">
                        Employee
                      </Label>
                      <Select
                        value={userFilter}
                        onValueChange={(v) => {
                          setUserFilter(v);
                          setPage(0);
                        }}
                      >
                        <SelectTrigger>
                          <SelectValue placeholder="All employees" />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem value="all">All employees</SelectItem>
                          {users.map((u) => (
                            <SelectItem key={u.id} value={u.id}>
                              {u.full_name}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>

                    <div className="space-y-1.5">
                      <Label className="text-xs text-muted-foreground">
                        From
                      </Label>
                      <Input
                        type="date"
                        value={fromDate}
                        onChange={(e) => {
                          setFromDate(e.target.value);
                          setPage(0);
                        }}
                        className="w-full sm:w-[160px]"
                      />
                    </div>

                    <div className="space-y-1.5">
                      <Label className="text-xs text-muted-foreground">
                        To
                      </Label>
                      <Input
                        type="date"
                        value={toDate}
                        onChange={(e) => {
                          setToDate(e.target.value);
                          setPage(0);
                        }}
                        className="w-full sm:w-[160px]"
                      />
                    </div>

                    {hasActiveFilters && (
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={resetFilters}
                        className="gap-1"
                      >
                        <X className="h-3.5 w-3.5" />
                        Clear filters
                      </Button>
                    )}
                  </div>
                </CardContent>
              </Card>

              <div className="rounded-lg border bg-card overflow-x-auto">
                {recordsLoading ? (
                  <TableSkeletonState label="Loading attendance…" />
                ) : recordsError ? (
                  <ErrorState message={recordsError} onRetry={fetchRecords} />
                ) : records.length === 0 ? (
                  <EmptyState
                    icon={<ListChecks className="h-8 w-8 opacity-30" />}
                    title="No attendance records found"
                    description={
                      hasActiveFilters
                        ? "Try widening your filters."
                        : "Records will show up here once employees start clocking in."
                    }
                  />
                ) : (
                  <Table className="min-w-[980px]">
                    <TableHeader>
                      <TableRow>
                        {showingUserColumn && <TableHead>Employee</TableHead>}
                        <TableHead>Date</TableHead>
                        <TableHead>Clock In</TableHead>
                        <TableHead>Clock Out</TableHead>
                        <TableHead>Duration</TableHead>
                        <TableHead>Photos</TableHead>
                        <TableHead>Checks</TableHead>
                        <TableHead>Status</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {records.map((rec) => (
                        <TableRow key={rec.id}>
                          {showingUserColumn && (
                            <TableCell>
                              <EmployeeCell
                                name={
                                  (rec as AttendanceWithUserDTO).full_name ??
                                  selectedUserName ??
                                  "—"
                                }
                                email={(rec as AttendanceWithUserDTO).email}
                              />
                            </TableCell>
                          )}
                          <TableCell className="whitespace-nowrap text-sm">
                            {formatDate(rec.date)}
                          </TableCell>
                          <TableCell className="whitespace-nowrap text-sm">
                            {formatTime(rec.check_in)}
                          </TableCell>
                          <TableCell className="whitespace-nowrap text-sm">
                            {formatTime(rec.check_out)}
                          </TableCell>
                          <TableCell className="whitespace-nowrap text-sm text-muted-foreground">
                            {formatDuration(rec.check_in, rec.check_out)}
                          </TableCell>
                          <TableCell>
                            <div className="flex gap-1.5">
                              <PhotoThumbnail
                                uri={rec.check_in_photo_uri}
                                label="Clock-in photo"
                              />
                              <PhotoThumbnail
                                uri={rec.check_out_photo_uri}
                                label="Clock-out photo"
                              />
                            </div>
                          </TableCell>
                          <TableCell>
                            <LocationChecksBadge
                              count={rec.location_checks_count}
                              within={rec.location_checks_within}
                            />
                          </TableCell>
                          <TableCell>
                            <ShiftStatusBadge
                              checkIn={rec.check_in}
                              checkOut={rec.check_out}
                            />
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
              </div>

              {!recordsLoading && !recordsError && total > 0 && (
                <div className="flex items-center justify-between">
                  <p className="text-xs text-muted-foreground">
                    Showing {page * PAGE_SIZE + 1}–
                    {Math.min(total, (page + 1) * PAGE_SIZE)} of {total}
                  </p>
                  <div className="flex items-center gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setPage((p) => Math.max(0, p - 1))}
                      disabled={page === 0}
                    >
                      <ChevronLeft className="h-4 w-4" />
                      Prev
                    </Button>
                    <span className="text-xs text-muted-foreground px-1">
                      Page {page + 1} of {totalPages}
                    </span>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() =>
                        setPage((p) => (p + 1 < totalPages ? p + 1 : p))
                      }
                      disabled={page + 1 >= totalPages}
                    >
                      Next
                      <ChevronRight className="h-4 w-4" />
                    </Button>
                  </div>
                </div>
              )}
            </TabsContent>

            {/* ------------------------------ Location Checks ------------------------------ */}
            <TabsContent value="location" className="mt-4 space-y-4">
              <Card>
                <CardContent className="pt-6">
                  <div className="flex flex-col sm:flex-row gap-3 sm:items-end">
                    <div className="space-y-1.5 flex-1 sm:max-w-[220px]">
                      <Label className="text-xs text-muted-foreground">
                        Employee
                      </Label>
                      <Select
                        value={locationUserFilter}
                        onValueChange={(v) => {
                          setLocationUserFilter(v);
                          setLocationPage(0);
                        }}
                      >
                        <SelectTrigger>
                          <SelectValue placeholder="All employees" />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem value="all">All employees</SelectItem>
                          {users.map((u) => (
                            <SelectItem key={u.id} value={u.id}>
                              {u.full_name}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>

                    <div className="space-y-1.5">
                      <Label className="text-xs text-muted-foreground">
                        From
                      </Label>
                      <Input
                        type="date"
                        value={locationFromDate}
                        onChange={(e) => {
                          setLocationFromDate(e.target.value);
                          setLocationPage(0);
                        }}
                        className="w-full sm:w-[160px]"
                      />
                    </div>

                    <div className="space-y-1.5">
                      <Label className="text-xs text-muted-foreground">
                        To
                      </Label>
                      <Input
                        type="date"
                        value={locationToDate}
                        onChange={(e) => {
                          setLocationToDate(e.target.value);
                          setLocationPage(0);
                        }}
                        className="w-full sm:w-[160px]"
                      />
                    </div>

                    {(locationUserFilter !== "all" ||
                      !!locationFromDate ||
                      !!locationToDate) && (
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={resetLocationFilters}
                        className="gap-1"
                      >
                        <X className="h-3.5 w-3.5" />
                        Clear filters
                      </Button>
                    )}
                  </div>
                </CardContent>
              </Card>

              {!locationLoading && !locationError && locationChecks.length > 0 && (
                <Card className="overflow-hidden">
                  <div className="h-[280px] sm:h-[340px] w-full">
                    <MapContainer
                      center={locationMapCenter}
                      zoom={15}
                      scrollWheelZoom={true}
                      className="h-full w-full"
                    >
                      <TileLayer
                        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
                        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
                      />
                      <FitMapToChecks points={locationMapPoints} />
                      {geofences.map((gf) => (
                        <Circle
                          key={gf.id}
                          center={[gf.center_lat, gf.center_lng]}
                          radius={gf.radius}
                          pathOptions={{
                            color: "hsl(var(--primary))",
                            fillOpacity: 0.08,
                          }}
                        />
                      ))}
                      {locationChecks.map((check) => (
                        <CircleMarker
                          key={check.id}
                          center={[check.latitude, check.longitude]}
                          radius={7}
                          pathOptions={{
                            color: check.within_geofence
                              ? "#10b981"
                              : "#ef4444",
                            fillColor: check.within_geofence
                              ? "#10b981"
                              : "#ef4444",
                            fillOpacity: 0.85,
                            weight: 2,
                          }}
                        />
                      ))}
                    </MapContainer>
                  </div>
                  <div className="flex flex-wrap items-center gap-4 border-t px-4 py-2 text-xs text-muted-foreground">
                    <span className="flex items-center gap-1.5">
                      <span className="h-2 w-2 rounded-full bg-emerald-500" />
                      Inside geofence
                    </span>
                    <span className="flex items-center gap-1.5">
                      <span className="h-2 w-2 rounded-full bg-red-500" />
                      Outside geofence
                    </span>
                    <span className="flex items-center gap-1.5">
                      <span className="h-2 w-2 rounded-full border-2 border-primary" />
                      Geofence boundary
                    </span>
                  </div>
                </Card>
              )}

              <div className="rounded-lg border bg-card overflow-x-auto">
                {locationLoading ? (
                  <TableSkeletonState label="Loading location checks…" />
                ) : locationError ? (
                  <ErrorState
                    message={locationError}
                    onRetry={fetchLocationChecks}
                  />
                ) : locationChecks.length === 0 ? (
                  <EmptyState
                    icon={<MapPin className="h-8 w-8 opacity-30" />}
                    title="No location checks found"
                    description="Location checks are captured automatically at fixed intervals during a shift."
                  />
                ) : (
                  <Table className="min-w-[860px]">
                    <TableHeader>
                      <TableRow>
                        <TableHead>Employee</TableHead>
                        <TableHead>Captured</TableHead>
                        <TableHead>Check</TableHead>
                        <TableHead>Location</TableHead>
                        <TableHead>Geofence</TableHead>
                        <TableHead>Distance</TableHead>
                        <TableHead>Accuracy</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {locationChecks.map((check) => (
                        <TableRow key={check.id}>
                          <TableCell>
                            <EmployeeCell
                              name={check.full_name}
                              email={check.email}
                            />
                          </TableCell>
                          <TableCell className="whitespace-nowrap text-sm">
                            {formatDateTime(check.captured_at)}
                          </TableCell>
                          <TableCell className="whitespace-nowrap text-sm text-muted-foreground">
                            #{check.sequence_number}
                          </TableCell>
                          <TableCell className="whitespace-nowrap text-sm">
                            <div className="flex items-center gap-2">
                              <Badge
                                variant={
                                  check.within_geofence
                                    ? "secondary"
                                    : "destructive"
                                }
                                className={
                                  check.within_geofence
                                    ? "bg-emerald-500/15 text-emerald-600 hover:bg-emerald-500/15 font-normal"
                                    : "font-normal"
                                }
                              >
                                {check.within_geofence ? "Inside" : "Outside"}
                              </Badge>
                              <span className="text-xs text-muted-foreground">
                                {formatCoords(
                                  check.latitude,
                                  check.longitude,
                                )}
                              </span>
                            </div>
                          </TableCell>
                          <TableCell className="text-sm">
                            {check.nearest_geofence_name ?? "—"}
                          </TableCell>
                          <TableCell className="whitespace-nowrap text-sm text-muted-foreground">
                            {check.distance_meters != null
                              ? `${Math.round(check.distance_meters)} m`
                              : "—"}
                          </TableCell>
                          <TableCell className="whitespace-nowrap text-sm text-muted-foreground">
                            {check.accuracy != null
                              ? `±${Math.round(check.accuracy)} m`
                              : "—"}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
              </div>

              {!locationLoading && !locationError && locationTotal > 0 && (
                <div className="flex items-center justify-between">
                  <p className="text-xs text-muted-foreground">
                    Showing{" "}
                    {locationPage * LOCATION_PAGE_SIZE + 1}–
                    {Math.min(
                      locationTotal,
                      (locationPage + 1) * LOCATION_PAGE_SIZE,
                    )}{" "}
                    of {locationTotal}
                  </p>
                  <div className="flex items-center gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() =>
                        setLocationPage((p) => Math.max(0, p - 1))
                      }
                      disabled={locationPage === 0}
                    >
                      <ChevronLeft className="h-4 w-4" />
                      Prev
                    </Button>
                    <span className="text-xs text-muted-foreground px-1">
                      Page {locationPage + 1} of {locationTotalPages}
                    </span>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() =>
                        setLocationPage((p) =>
                          p + 1 < locationTotalPages ? p + 1 : p,
                        )
                      }
                      disabled={locationPage + 1 >= locationTotalPages}
                    >
                      Next
                      <ChevronRight className="h-4 w-4" />
                    </Button>
                  </div>
                </div>
              )}
            </TabsContent>

            {/* --------------------------------- Calendar --------------------------------- */}
            <TabsContent value="calendar" className="mt-4 space-y-4">
              <Card>
                <CardContent className="pt-6">
                  <div className="space-y-1.5 max-w-[260px]">
                    <Label className="text-xs text-muted-foreground">
                      Employee
                    </Label>
                    <Select
                      value={calendarUserId}
                      onValueChange={(v) => {
                        setCalendarUserId(v);
                        setSelectedDay(undefined);
                      }}
                    >
                      <SelectTrigger>
                        <SelectValue placeholder="Choose an employee" />
                      </SelectTrigger>
                      <SelectContent>
                        {users.map((u) => (
                          <SelectItem key={u.id} value={u.id}>
                            {u.full_name}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                </CardContent>
              </Card>

              {!calendarUserId ? (
                <div className="rounded-lg border bg-card">
                  <EmptyState
                    icon={<CalendarDays className="h-8 w-8 opacity-30" />}
                    title="Choose an employee"
                    description="Pick someone from the dropdown above to see their attendance calendar."
                  />
                </div>
              ) : calendarError ? (
                <div className="rounded-lg border bg-card">
                  <ErrorState
                    message={calendarError}
                    onRetry={() => setCalendarMonth(new Date(calendarMonth))}
                  />
                </div>
              ) : (
                <div className="flex flex-col lg:flex-row gap-4">
                  <Card className="w-fit">
                    <CardContent className="pt-6 space-y-3">
                      {calendarLoading && (
                        <div className="flex items-center gap-2 text-xs text-muted-foreground">
                          <Loader2 className="h-3.5 w-3.5 animate-spin" />
                          Loading month…
                        </div>
                      )}
                      <Calendar
                        mode="single"
                        month={calendarMonth}
                        onMonthChange={setCalendarMonth}
                        selected={selectedDay}
                        onSelect={setSelectedDay}
                        modifiers={{
                          complete: completeDays,
                          open: openDays,
                          missing: missingDays,
                        }}
                        modifiersClassNames={{
                          complete:
                            "bg-emerald-500/15 text-emerald-700 rounded-md",
                          open: "bg-amber-500/15 text-amber-700 rounded-md",
                          missing: "text-muted-foreground/50",
                        }}
                      />
                      <div className="flex flex-wrap items-center gap-3 text-xs text-muted-foreground pt-1">
                        <span className="flex items-center gap-1.5">
                          <span className="h-2 w-2 rounded-full bg-emerald-500" />{" "}
                          Complete
                        </span>
                        <span className="flex items-center gap-1.5">
                          <span className="h-2 w-2 rounded-full bg-amber-500" />{" "}
                          Open shift
                        </span>
                        <span className="flex items-center gap-1.5">
                          <span className="h-2 w-2 rounded-full bg-muted-foreground/40" />{" "}
                          No record
                        </span>
                      </div>
                    </CardContent>
                  </Card>

                  <Card className="flex-1">
                    <CardContent className="pt-6 space-y-4">
                      {!selectedDay ? (
                        <p className="text-sm text-muted-foreground">
                          Select a day on the calendar to see the details.
                        </p>
                      ) : (
                        <>
                          <p className="text-sm font-medium">
                            {selectedDay.toLocaleDateString(undefined, {
                              weekday: "long",
                              year: "numeric",
                              month: "long",
                              day: "numeric",
                            })}
                          </p>
                          {!selectedDayRecord ? (
                            <p className="text-sm text-muted-foreground">
                              No attendance record for this day.
                            </p>
                          ) : (
                            <div className="grid gap-4 sm:grid-cols-2">
                              <div className="space-y-1">
                                <p className="text-xs text-muted-foreground">
                                  Clock in
                                </p>
                                <p className="text-sm font-medium">
                                  {formatTime(selectedDayRecord.check_in)}
                                </p>
                              </div>
                              <div className="space-y-1">
                                <p className="text-xs text-muted-foreground">
                                  Clock out
                                </p>
                                <p className="text-sm font-medium">
                                  {formatTime(selectedDayRecord.check_out)}
                                </p>
                              </div>
                              <div className="space-y-1.5">
                                <p className="text-xs text-muted-foreground">
                                  Clock-in photo
                                </p>
                                <PhotoThumbnail
                                  uri={selectedDayRecord.check_in_photo_uri}
                                  label="Clock-in photo"
                                />
                              </div>
                              <div className="space-y-1.5">
                                <p className="text-xs text-muted-foreground">
                                  Clock-out photo
                                </p>
                                <PhotoThumbnail
                                  uri={selectedDayRecord.check_out_photo_uri}
                                  label="Clock-out photo"
                                />
                              </div>
                              <div className="space-y-1.5 sm:col-span-2">
                                <p className="text-xs text-muted-foreground">
                                  Location checks passed
                                </p>
                                <LocationChecksBadge
                                  count={selectedDayRecord.location_checks_count}
                                  within={
                                    selectedDayRecord.location_checks_within
                                  }
                                />
                              </div>
                            </div>
                          )}
                        </>
                      )}
                    </CardContent>
                  </Card>
                </div>
              )}
            </TabsContent>

            {/* -------------------------------- Currently In -------------------------------- */}
            <TabsContent value="open" className="mt-4 space-y-4">
              <Card>
                <CardContent className="pt-6">
                  <div className="space-y-1.5 max-w-[200px]">
                    <Label className="text-xs text-muted-foreground">
                      Date
                    </Label>
                    <Input
                      type="date"
                      value={openDate}
                      onChange={(e) => setOpenDate(e.target.value)}
                    />
                    <p className="text-xs text-muted-foreground">
                      Defaults to today.
                    </p>
                  </div>
                </CardContent>
              </Card>

              <div className="rounded-lg border bg-card overflow-x-auto">
                {openLoading ? (
                  <TableSkeletonState label="Loading open shifts…" />
                ) : openError ? (
                  <ErrorState message={openError} onRetry={fetchOpenShifts} />
                ) : openShifts.length === 0 ? (
                  <EmptyState
                    icon={<Clock className="h-8 w-8 opacity-30" />}
                    title="No one is currently clocked in"
                    description="Employees who clock in without clocking out will show up here."
                  />
                ) : (
                  <Table className="min-w-[640px]">
                    <TableHeader>
                      <TableRow>
                        <TableHead>Employee</TableHead>
                        <TableHead>Date</TableHead>
                        <TableHead>Clock In</TableHead>
                        <TableHead>Elapsed</TableHead>
                        <TableHead>Photo</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {openShifts.map((rec) => (
                        <TableRow key={rec.id}>
                          <TableCell>
                            <EmployeeCell
                              name={rec.full_name}
                              email={rec.email}
                            />
                          </TableCell>
                          <TableCell className="whitespace-nowrap text-sm">
                            {formatDate(rec.date)}
                          </TableCell>
                          <TableCell className="whitespace-nowrap text-sm">
                            {formatTime(rec.check_in)}
                          </TableCell>
                          <TableCell className="whitespace-nowrap text-sm text-muted-foreground">
                            {formatDuration(
                              rec.check_in,
                              new Date().toISOString(),
                            )}
                          </TableCell>
                          <TableCell>
                            <PhotoThumbnail
                              uri={rec.check_in_photo_uri}
                              label="Clock-in photo"
                            />
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
              </div>
            </TabsContent>

            {/* -------------------------------- Absent -------------------------------- */}
            <TabsContent value="absent" className="mt-4 space-y-4">
              <Card>
                <CardContent className="pt-6">
                  <div className="space-y-1.5 max-w-[200px]">
                    <Label className="text-xs text-muted-foreground">
                      Date
                    </Label>
                    <Input
                      type="date"
                      value={absentDate}
                      onChange={(e) => setAbsentDate(e.target.value)}
                    />
                    <p className="text-xs text-muted-foreground">
                      Defaults to today.
                    </p>
                  </div>
                </CardContent>
              </Card>

              <div className="rounded-lg border bg-card overflow-x-auto">
                {absentLoading ? (
                  <TableSkeletonState label="Loading absentees…" />
                ) : absentError ? (
                  <ErrorState
                    message={absentError}
                    onRetry={fetchAbsentUsers}
                  />
                ) : absentUsers.length === 0 ? (
                  <EmptyState
                    icon={<UserX className="h-8 w-8 opacity-30" />}
                    title="Everyone has clocked in"
                    description="No absences recorded for this date."
                  />
                ) : (
                  <Table className="min-w-[480px]">
                    <TableHeader>
                      <TableRow>
                        <TableHead>Employee</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {absentUsers.map((u) => (
                        <TableRow key={u.id}>
                          <TableCell>
                            <EmployeeCell name={u.full_name} email={u.email} />
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
              </div>
            </TabsContent>

            {/* -------------------------------- Stale Shifts -------------------------------- */}
            <TabsContent value="stale" className="mt-4 space-y-4">
              <p className="text-sm text-muted-foreground">
                Shifts that were clocked in but never closed out, and are now
                past the 24-hour update window.
              </p>

              <div className="rounded-lg border bg-card overflow-x-auto">
                {staleLoading ? (
                  <TableSkeletonState label="Loading stale shifts…" />
                ) : staleError ? (
                  <ErrorState message={staleError} onRetry={fetchStaleShifts} />
                ) : staleShifts.length === 0 ? (
                  <EmptyState
                    icon={<CalendarClock className="h-8 w-8 opacity-30" />}
                    title="No stale shifts"
                    description="Every past shift has been properly closed out."
                  />
                ) : (
                  <Table className="min-w-[640px]">
                    <TableHeader>
                      <TableRow>
                        <TableHead>Employee</TableHead>
                        <TableHead>Date</TableHead>
                        <TableHead>Clock In</TableHead>
                        <TableHead>Photo</TableHead>
                        <TableHead>Status</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {staleShifts.map((rec) => (
                        <TableRow key={rec.id}>
                          <TableCell>
                            <EmployeeCell
                              name={rec.full_name}
                              email={rec.email}
                            />
                          </TableCell>
                          <TableCell className="whitespace-nowrap text-sm">
                            {formatDate(rec.date)}
                          </TableCell>
                          <TableCell className="whitespace-nowrap text-sm">
                            {formatTime(rec.check_in)}
                          </TableCell>
                          <TableCell>
                            <PhotoThumbnail
                              uri={rec.check_in_photo_uri}
                              label="Clock-in photo"
                            />
                          </TableCell>
                          <TableCell>
                            <ShiftStatusBadge checkIn={rec.check_in} stale />
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
              </div>
            </TabsContent>
          </Tabs>
        </main>
      </div>

  );
}
