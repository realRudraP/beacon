import { useEffect, useState, useMemo } from "react";
import { Link } from "react-router-dom";
import {
  Loader2,
  Plus,
  Search,
  Wallet,
  ArrowUpRight,
  ArrowDownLeft,
  Clock,
  ChevronRight,
} from "lucide-react";


import api from "@/api/axiosClient";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
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
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
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

interface WorkerCashSummary {
  user_id: string;
  full_name: string;
  phone_number: string | null;
  active: boolean;
  current_balance: number;
  total_credited: number;
  total_debited: number;
  last_transaction_at: string | null;
}

export default function CashPage() {


  const [loading, setLoading] = useState(true);
  const [workers, setWorkers] = useState<WorkerCashSummary[]>([]);
  const [searchQuery, setSearchQuery] = useState("");

  // Top-Up Dialog State
  const [isTopUpOpen, setIsTopUpOpen] = useState(false);
  const [topUpWorkerId, setTopUpWorkerId] = useState("");
  const [topUpAmount, setTopUpAmount] = useState("");
  const [topUpCategory, setTopUpCategory] = useState("topup");
  const [topUpNotes, setTopUpNotes] = useState("");
  const [topUpSubmitting, setTopUpSubmitting] = useState(false);
  const [topUpError, setTopUpError] = useState<string | null>(null);

  async function fetchSummary() {
    try {
      setLoading(true);
      const { data } = await api.get("/cash/summary");
      setWorkers(data || []);
    } catch (err) {
      console.error("Failed to load cash summary", err);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    fetchSummary();
  }, []);

  async function handleTopUpSubmit(e: React.FormEvent) {
    e.preventDefault();
    const amountNum = parseFloat(topUpAmount);
    if (!topUpWorkerId || isNaN(amountNum) || amountNum <= 0) {
      setTopUpError("Please enter a valid amount and select a worker.");
      return;
    }

    setTopUpSubmitting(true);
    setTopUpError(null);
    try {
      await api.post("/cash/transactions", {
        id: crypto.randomUUID(),
        user_id: topUpWorkerId,
        type: "credit",
        amount: amountNum,
        category: topUpCategory,
        description: topUpNotes.trim() || undefined,
      });

      setIsTopUpOpen(false);
      setTopUpAmount("");
      setTopUpNotes("");
      setTopUpWorkerId("");
      fetchSummary();
    } catch (err: any) {
      setTopUpError(err.response?.data?.message || "Failed to disburse cash.");
    } finally {
      setTopUpSubmitting(false);
    }
  }

  const totalInHand = useMemo(() => workers.reduce((acc, w) => acc + w.current_balance, 0), [workers]);
  const totalDisbursed = useMemo(() => workers.reduce((acc, w) => acc + w.total_credited, 0), [workers]);
  const totalSpent = useMemo(() => workers.reduce((acc, w) => acc + w.total_debited, 0), [workers]);

  const filteredWorkers = useMemo(() => {
    return workers.filter((w) =>
      w.full_name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      (w.phone_number && w.phone_number.includes(searchQuery))
    );
  }, [workers, searchQuery]);

  return (
<>

          <div className="space-y-6">
            {/* Header */}
            <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
              <div>
                <h1 className="text-2xl font-bold tracking-tight">Petty Cash Ledger</h1>
                <p className="text-sm text-muted-foreground">
                  Track worker balances, disburse cash, and audit expenditures.
                </p>
              </div>
              <Button
                className="w-full sm:w-auto"
                onClick={() => {
                  setTopUpWorkerId("");
                  setIsTopUpOpen(true);
                }}
              >
                <Plus className="mr-2 h-4 w-4" /> Disburse Cash
              </Button>
            </div>

            {/* KPI Cards (Responsive Grid) */}
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
              <Card>
                <CardHeader className="flex flex-row items-center justify-between pb-2">
                  <CardTitle className="text-sm font-medium">Total Cash in Field</CardTitle>
                  <Wallet className="h-4 w-4 text-muted-foreground" />
                </CardHeader>
                <CardContent>
                  <div className="text-2xl font-bold">₹{totalInHand.toFixed(2)}</div>
                  <p className="text-xs text-muted-foreground">Available balance with all workers</p>
                </CardContent>
              </Card>

              <Card>
                <CardHeader className="flex flex-row items-center justify-between pb-2">
                  <CardTitle className="text-sm font-medium">Total Disbursed</CardTitle>
                  <ArrowDownLeft className="h-4 w-4 text-emerald-500" />
                </CardHeader>
                <CardContent>
                  <div className="text-2xl font-bold">₹{totalDisbursed.toFixed(2)}</div>
                  <p className="text-xs text-muted-foreground">All-time cash given</p>
                </CardContent>
              </Card>

              <Card className="sm:col-span-2 lg:col-span-1">
                <CardHeader className="flex flex-row items-center justify-between pb-2">
                  <CardTitle className="text-sm font-medium">Total Spent</CardTitle>
                  <ArrowUpRight className="h-4 w-4 text-rose-500" />
                </CardHeader>
                <CardContent>
                  <div className="text-2xl font-bold">₹{totalSpent.toFixed(2)}</div>
                  <p className="text-xs text-muted-foreground">All-time logged expenses</p>
                </CardContent>
              </Card>
            </div>

            {/* Search Bar */}
            <div className="flex items-center gap-2 max-w-sm">
              <div className="relative w-full">
                <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
                <Input
                  placeholder="Search worker by name or phone..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="pl-8"
                />
              </div>
            </div>

            {/* Workers Summary Table */}
            <Card className="overflow-hidden">
              <CardContent className="p-0">
                {loading ? (
                  <div className="flex items-center justify-center py-20 text-muted-foreground gap-2">
                    <Loader2 className="h-5 w-5 animate-spin" /> Loading cash ledger...
                  </div>
                ) : filteredWorkers.length === 0 ? (
                  <div className="text-center py-16 text-muted-foreground text-sm">
                    No worker records found.
                  </div>
                ) : (
                  <div className="overflow-x-auto">
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>Worker</TableHead>
                          <TableHead className="hidden md:table-cell">Phone</TableHead>
                          <TableHead className="text-right hidden sm:table-cell">Disbursed</TableHead>
                          <TableHead className="text-right hidden sm:table-cell">Spent</TableHead>
                          <TableHead className="text-right">Balance</TableHead>
                          <TableHead className="hidden lg:table-cell">Last Activity</TableHead>
                          <TableHead className="text-right">Actions</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {filteredWorkers.map((worker) => {
                          const isPositive = worker.current_balance >= 0;
                          return (
                            <TableRow key={worker.user_id}>
                              <TableCell className="font-medium">
                                <Link
                                  to={`/cash/${worker.user_id}`}
                                  className="hover:underline text-foreground block"
                                >
                                  {worker.full_name}
                                </Link>
                                <div className="text-xs text-muted-foreground md:hidden mt-0.5">
                                  {worker.phone_number || "No phone"}
                                </div>
                              </TableCell>
                              <TableCell className="hidden md:table-cell text-muted-foreground">
                                {worker.phone_number || "—"}
                              </TableCell>
                              <TableCell className="text-right text-emerald-600 font-medium hidden sm:table-cell">
                                +₹{worker.total_credited.toFixed(2)}
                              </TableCell>
                              <TableCell className="text-right text-rose-500 font-medium hidden sm:table-cell">
                                -₹{worker.total_debited.toFixed(2)}
                              </TableCell>
                              <TableCell className="text-right font-bold">
                                <Badge
                                  variant={isPositive ? "secondary" : "destructive"}
                                  className={isPositive ? "bg-emerald-500/15 text-emerald-600 border-0" : ""}
                                >
                                  ₹{worker.current_balance.toFixed(2)}
                                </Badge>
                              </TableCell>
                              <TableCell className="text-xs text-muted-foreground hidden lg:table-cell">
                                {worker.last_transaction_at ? (
                                  <span className="flex items-center gap-1">
                                    <Clock className="h-3 w-3" />
                                    {new Date(worker.last_transaction_at).toLocaleDateString()}
                                  </span>
                                ) : (
                                  "No transactions"
                                )}
                              </TableCell>
                              <TableCell className="text-right space-x-1 sm:space-x-2">
                                <Button
                                  variant="outline"
                                  size="sm"
                                  onClick={() => {
                                    setTopUpWorkerId(worker.user_id);
                                    setIsTopUpOpen(true);
                                  }}
                                >
                                  Top-up
                                </Button>
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  asChild
                                >
                                  <Link to={`/cash/${worker.user_id}`}>
                                    <span className="hidden sm:inline">Ledger</span>
                                    <ChevronRight className="h-4 w-4 sm:ml-1" />
                                  </Link>
                                </Button>
                              </TableCell>
                            </TableRow>
                          );
                        })}
                      </TableBody>
                    </Table>
                  </div>
                )}
              </CardContent>
            </Card>
          </div>

          {/* Top-Up Dialog */}
          <Dialog open={isTopUpOpen} onOpenChange={setIsTopUpOpen}>
            <DialogContent className="sm:max-w-[425px]">
              <form onSubmit={handleTopUpSubmit}>
                <DialogHeader>
                  <DialogTitle>Disburse Petty Cash</DialogTitle>
                  <DialogDescription>
                    Credit money to a worker's account.
                  </DialogDescription>
                </DialogHeader>

                <div className="grid gap-4 py-4">
                  {topUpError && (
                    <div className="rounded-md bg-destructive/15 p-3 text-sm text-destructive font-medium">
                      {topUpError}
                    </div>
                  )}

                  <div className="space-y-1.5">
                    <Label htmlFor="worker">Worker</Label>
                    <Select value={topUpWorkerId} onValueChange={setTopUpWorkerId}>
                      <SelectTrigger>
                        <SelectValue placeholder="Select worker" />
                      </SelectTrigger>
                      <SelectContent>
                        {workers.map((w) => (
                          <SelectItem key={w.user_id} value={w.user_id}>
                            {w.full_name} (Bal: ₹{w.current_balance.toFixed(2)})
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>

                  <div className="space-y-1.5">
                    <Label htmlFor="amount">Amount (₹)</Label>
                    <Input
                      id="amount"
                      type="number"
                      step="0.01"
                      min="1"
                      placeholder="e.g. 2000"
                      value={topUpAmount}
                      onChange={(e) => setTopUpAmount(e.target.value)}
                      required
                    />
                  </div>

                  <div className="space-y-1.5">
                    <Label htmlFor="category">Category</Label>
                    <Select value={topUpCategory} onValueChange={setTopUpCategory}>
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="topup">Petty Cash Top-up</SelectItem>
                        <SelectItem value="fuel">Fuel Allowance</SelectItem>
                        <SelectItem value="emergency">Emergency Advance</SelectItem>
                        <SelectItem value="other">Other</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>

                  <div className="space-y-1.5">
                    <Label htmlFor="notes">Notes / Purpose (Optional)</Label>
                    <Input
                      id="notes"
                      placeholder="e.g. For generator diesel at East field"
                      value={topUpNotes}
                      onChange={(e) => setTopUpNotes(e.target.value)}
                    />
                  </div>
                </div>

                <DialogFooter className="gap-2 sm:gap-0">
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => setIsTopUpOpen(false)}
                    disabled={topUpSubmitting}
                  >
                    Cancel
                  </Button>
                  <Button type="submit" disabled={topUpSubmitting}>
                    {topUpSubmitting && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
                    Confirm Top-up
                  </Button>
                </DialogFooter>
              </form>
            </DialogContent>
          </Dialog>
        </>
  );
}
