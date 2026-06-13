import { useState, useEffect, useRef, useMemo } from "react";
import { SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar";
import { AppSidebar } from "@/components/app-sidebar";
import { UserMenu } from "./UserMenu";
import { useSystemState } from "@/context/ConfigContext";
import api from "@/api/axiosClient";
import { Loader2, Trash2, MapPin } from "lucide-react";

// Shadcn UI
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

// Leaflet
import { MapContainer, TileLayer, Marker, Circle, useMap } from "react-leaflet";
import L from "leaflet";
import "leaflet/dist/leaflet.css";

// --- THE LEAFLET ICON FIX ---
import icon from "leaflet/dist/images/marker-icon.png";
import iconShadow from "leaflet/dist/images/marker-shadow.png";
const DefaultIcon = L.icon({
  iconUrl: icon,
  shadowUrl: iconShadow,
  iconSize: [25, 41],
  iconAnchor: [12, 41],
});
L.Marker.prototype.options.icon = DefaultIcon;
// ----------------------------

type Geofence = {
  id: string;
  name: string;
  center_lat: number;
  center_lng: number;
  radius: number;
  created_at: string;
};

// ─── Inner map component: flies to position on change, skipping initial mount ───
function FlyToPosition({ lat, lng }: { lat: number; lng: number }) {
  const map = useMap();
  const isFirstRender = useRef(true);

  useEffect(() => {
    if (isFirstRender.current) {
      isFirstRender.current = false;
      return;
    }
    map.flyTo([lat, lng], map.getZoom(), { duration: 0.8 });
  }, [lat, lng, map]);

  return null;
}

const DEFAULT_LAT = 28.6139;
const DEFAULT_LNG = 77.209;

export default function GeofencesPage() {
  const config = useSystemState();

  // State for the list
  const [geofences, setGeofences] = useState<Geofence[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Form state
  const [formName, setFormName] = useState("");
  const [radius, setRadius] = useState(100);
  const [position, setPosition] = useState({
    lat: DEFAULT_LAT,
    lng: DEFAULT_LNG,
  });
  const [submitting, setSubmitting] = useState(false);

  // Coordinate input strings — kept separate so typing mid-number doesn't reset the cursor
  const [latInput, setLatInput] = useState(DEFAULT_LAT.toString());
  const [lngInput, setLngInput] = useState(DEFAULT_LNG.toString());

  // Track which existing geofence is highlighted
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const markerRef = useRef<L.Marker>(null);

  useEffect(() => {
    fetchGeofences();
  }, []);

  const fetchGeofences = async () => {
    try {
      setLoading(true);
      const { data } = await api.get("/geofences");
      setGeofences(data || []);
      setError(null);
    } catch (err: any) {
      setError(err.response?.data?.message || "Failed to load geofences");
    } finally {
      setLoading(false);
    }
  };

  // ─── Shared helper: update position + keep string inputs in sync ───
  const applyPosition = (lat: number, lng: number) => {
    setPosition({ lat, lng });
    setLatInput(lat.toFixed(6));
    setLngInput(lng.toFixed(6));
  };

  // ─── Coordinate input handlers: update string immediately, parse position if valid ───
  const handleLatInput = (value: string) => {
    setLatInput(value);
    const num = parseFloat(value);
    if (!isNaN(num) && num >= -90 && num <= 90) {
      setPosition((prev) => ({ ...prev, lat: num }));
    }
  };

  const handleLngInput = (value: string) => {
    setLngInput(value);
    const num = parseFloat(value);
    if (!isNaN(num) && num >= -180 && num <= 180) {
      setPosition((prev) => ({ ...prev, lng: num }));
    }
  };

  // ─── Clicking a geofence row loads its data into the form and flies the map ───
  const handleGeofenceRowClick = (gf: Geofence) => {
    setSelectedId(gf.id);
    setFormName(gf.name);
    setRadius(gf.radius);
    applyPosition(gf.center_lat, gf.center_lng);
  };

  // ─── Marker drag: update position + string inputs ───
  const eventHandlers = useMemo(
    () => ({
      dragend() {
        const marker = markerRef.current;
        if (marker != null) {
          const { lat, lng } = marker.getLatLng();
          applyPosition(lat, lng);
        }
      },
    }),
    [],
  );

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formName.trim()) {
      setError("Please provide a name for the geofence.");
      return;
    }
    try {
      setSubmitting(true);
      setError(null);
      await api.post("/geofences", {
        name: formName,
        centre_lat: position.lat,
        centre_lng: position.lng,
        radius: Number(radius),
      });
      setFormName("");
      setRadius(100);
      setSelectedId(null);
      fetchGeofences();
    } catch (err: any) {
      setError(err.response?.data?.message || "Failed to create geofence");
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (id: string) => {
    if (!window.confirm("Are you sure you want to delete this geofence?"))
      return;
    try {
      await api.delete(`/geofences/${id}`);
      setGeofences((prev) => prev.filter((g) => g.id !== id));
      if (selectedId === id) setSelectedId(null);
    } catch (err: any) {
      alert(err.response?.data?.message || "Failed to delete geofence");
    }
  };

  return (
    <SidebarProvider>
      <div className="flex min-h-screen w-full bg-muted/40">
        <AppSidebar />

        <main className="flex-1 p-6">
          {/* Header */}
          <div className="flex items-center justify-between border-b pb-3 mb-6">
            <div className="flex items-center gap-4">
              <SidebarTrigger />
              <span className="font-semibold text-lg tracking-tight">
                {config.branding.business_name} - Geofences
              </span>
            </div>
            <UserMenu />
          </div>

          {error && (
            <div className="mb-4 rounded-md bg-destructive/15 p-3 text-sm text-destructive font-medium">
              {error}
            </div>
          )}

          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            {/* LEFT: Editor */}
            <Card>
              <CardHeader>
                <CardTitle>Add New Site</CardTitle>
                <CardDescription>
                  Drag the marker, type coordinates, or click an existing site
                  to set the center.
                </CardDescription>
              </CardHeader>
              <CardContent>
                <form onSubmit={handleCreate} className="space-y-6">
                  <div className="space-y-2">
                    <Label htmlFor="name">Site Name</Label>
                    <Input
                      id="name"
                      placeholder="e.g. North Field"
                      value={formName}
                      onChange={(e) => setFormName(e.target.value)}
                      required
                    />
                  </div>

                  <div className="space-y-2">
                    <div className="flex justify-between">
                      <Label>Radius (Meters)</Label>
                      <span className="text-sm text-muted-foreground">
                        {radius}m
                      </span>
                    </div>
                    <input
                      type="range"
                      min="10"
                      max="2000"
                      step="10"
                      value={radius}
                      onChange={(e) => setRadius(Number(e.target.value))}
                      className="w-full accent-primary"
                    />
                  </div>

                  {/* ── Coordinate inputs ── */}
                  <div className="grid grid-cols-2 gap-4">
                    <div className="space-y-2">
                      <Label htmlFor="lat">Latitude</Label>
                      <Input
                        id="lat"
                        type="number"
                        step="any"
                        min="-90"
                        max="90"
                        placeholder="e.g. 28.6139"
                        value={latInput}
                        onChange={(e) => handleLatInput(e.target.value)}
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="lng">Longitude</Label>
                      <Input
                        id="lng"
                        type="number"
                        step="any"
                        min="-180"
                        max="180"
                        placeholder="e.g. 77.2090"
                        value={lngInput}
                        onChange={(e) => handleLngInput(e.target.value)}
                      />
                    </div>
                  </div>

                  {/* ── Map ── */}
                  <div className="relative h-[300px] w-full rounded-md overflow-hidden border">
                    <MapContainer
                      center={[position.lat, position.lng]}
                      zoom={14}
                      scrollWheelZoom={true}
                      className="h-full w-full"
                    >
                      <TileLayer
                        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
                        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
                      />
                      {/* Handles panning on position change */}
                      <FlyToPosition lat={position.lat} lng={position.lng} />
                      <Marker
                        position={[position.lat, position.lng]}
                        draggable={true}
                        eventHandlers={eventHandlers}
                        ref={markerRef}
                      />
                      <Circle
                        center={[position.lat, position.lng]}
                        radius={radius}
                        color="hsl(var(--primary))"
                      />
                    </MapContainer>
                  </div>

                  <Button
                    type="submit"
                    className="w-full"
                    disabled={submitting}
                  >
                    {submitting && (
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    )}
                    Save Geofence
                  </Button>
                </form>
              </CardContent>
            </Card>

            {/* RIGHT: List */}
            <Card>
              <CardHeader>
                <CardTitle>Active Geofences</CardTitle>
                <CardDescription>
                  Click a row to preview it on the map.
                </CardDescription>
              </CardHeader>
              <CardContent>
                {loading ? (
                  <div className="flex justify-center p-8">
                    <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
                  </div>
                ) : geofences.length === 0 ? (
                  <div className="flex flex-col items-center justify-center py-12 text-center border-2 border-dashed rounded-md">
                    <MapPin className="h-12 w-12 text-muted-foreground/50 mb-4" />
                    <h3 className="text-lg font-medium">No geofences found</h3>
                    <p className="text-sm text-muted-foreground">
                      Create one using the map to get started.
                    </p>
                  </div>
                ) : (
                  <div className="rounded-md border">
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>Name</TableHead>
                          <TableHead>Radius</TableHead>
                          <TableHead className="text-right">Actions</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {geofences.map((gf) => (
                          <TableRow
                            key={gf.id}
                            className={`cursor-pointer transition-colors ${
                              selectedId === gf.id
                                ? "bg-primary/10 hover:bg-primary/15"
                                : "hover:bg-muted/60"
                            }`}
                            onClick={() => handleGeofenceRowClick(gf)}
                          >
                            <TableCell className="font-medium">
                              {gf.name}
                            </TableCell>
                            <TableCell>{gf.radius}m</TableCell>
                            <TableCell className="text-right">
                              <Button
                                variant="ghost"
                                size="icon"
                                className="text-destructive hover:bg-destructive/10 hover:text-destructive"
                                onClick={(e) => {
                                  e.stopPropagation();
                                  handleDelete(gf.id);
                                }}
                              >
                                <Trash2 className="h-4 w-4" />
                              </Button>
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </div>
                )}
              </CardContent>
            </Card>
          </div>
        </main>
      </div>
    </SidebarProvider>
  );
}
