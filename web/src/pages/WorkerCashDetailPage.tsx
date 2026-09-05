import { useEffect, useState, useMemo } from "react";
import { useParams, Link } from "react-router-dom";
import {
  ArrowLeft,
  Loader2,
  Plus,
  Search,
  ArrowUpRight,
  ArrowDownLeft,
  Receipt,
  FileText,
  RotateCcw,
} from "lucide-react";

import { SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar";
import { AppSidebar } from "@/components/app-sidebar";
import { UserMenu } from "./UserMenu";
import { useSystemState } from "@/context/ConfigContext";
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
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

interface CashTransaction {
  id: string;
  user_id: string;
  type: "credit" | "debit";
  amount: number;
  category: string;
  description: string | null;
  receipt_photo_uri: string | null;
  created_by: string;
  created_at: string;
}

export default function WorkerCashDetailPage() {
  const { userId } = useParams<{ userId: string }>();
  const { branding } = useSystemState();

  const [loading, setLoading] = useState(true);
  const [balance, setBalance] = useState(0);
  const [transactions, setTransactions] = useState<CashTransaction[]>([]);
  const [workerName, setWorkerName] = useState("Worker");

  // Filters
  const [typeFilter, setTypeFilter] = useState<"all" | "credit" | "debit">("all");
  const [fromDate, setFromDate] = useState("");
  const [toDate, setToDate] = useState("");
  const [searchQuery, setSearchQuery] = useState("");

  // Top-Up Dialog
  const [isTopUpOpen, setIsTopUpOpen] = useState(false);
  const [topUpAmount, setTopUpAmount] = useState("");
  const [topUpCategory, setTopUpCategory] = useState("topup");
  const [topUpNotes, setTopUpNotes] = useState("");
  const [topUpSubmitting, setTopUpSubmitting] = useState(false);

  async function fetchLedger() {
    if (!userId) return;
    try {
      setLoading(true);
      const [ledgerRes, summaryRes] = await Promise.all([
        api.get(`/cash/transactions?user_id=${userId}`),
        api.get(`/cash/summary`),
      ]);

      setBalance(ledgerRes.data.balance || 0);
      setTransactions(ledgerRes.data.transactions || []);

      const currentWorker = summaryRes.data?.find((w: any) => w.user_id === userId);
      if (currentWorker) setWorkerName(currentWorker.full_name);
    } catch (err) {
      console.error("Failed to load worker ledger", err);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    fetchLedger();
  }, [userId]);

  async function handleTopUpSubmit(e: React.FormEvent) {
    e.preventDefault();
    const amountNum = parseFloat(topUpAmount);
    if (!userId || isNaN(amountNum) || amountNum <= 0) return;

    setTopUpSubmitting(true);
    try {
      await api.post("/cash/transactions", {
        id: crypto.randomUUID(),
        user_id: userId,
        type: "credit",
        amount: amountNum,
        category: topUpCategory,
        description: topUpNotes.trim() || undefined,
      });

      setIsTopUpOpen(false);
      setTopUpAmount("");
      setTopUpNotes("");
      fetchLedger();
    } catch (err) {
      console.error("Failed to top-up", err);
    } finally {
      setTopUpSubmitting(false);
    }
  }

  function resetFilters() {
    setTypeFilter("all");
    setFromDate("");
    setToDate("");
    setSearchQuery("");
  }

  // Filtered list
  const filteredTransactions = useMemo(() => {
    return transactions.filter((tx) => {
      if (typeFilter !== "all" && tx.type !== typeFilter) return false;

      const txDate = tx.created_at.split("T")[0];
      if (fromDate && txDate < fromDate) return false;
      if (toDate && txDate > toDate) return false;

      if (searchQuery) {
        const q = searchQuery.toLowerCase();
        const matchCat = tx.category.toLowerCase().includes(q);
        const matchDesc = tx.description?.toLowerCase().includes(q) ?? false;
        if (!matchCat && !matchDesc) return false;
      }

      return true;
    });
  }, [transactions, typeFilter, fromDate, toDate, searchQuery]);

  const totalCredited = useMemo(
    () => transactions.filter((t) => t.type === "credit").reduce((sum, t) => sum + t.amount, 0),
    [transactions]
  );
  const totalDebited = useMemo(
    () => transactions.filter((t) => t.type === "debit").reduce((sum, t) => sum + t.amount, 0),
    [transactions]
  );

  return (
    <SidebarProvider>
      <div className="flex min-h-screen w-full">
        <AppSidebar />
        <main className="flex-1 p-4 md:p-6 max-w-full overflow-x-hidden">
          <div className="flex items-center justify-between border-b pb-3 mb-4">
            <div className="flex items-center gap-4">
              <SidebarTrigger />
              <span className="font-semibold text-lg tracking-tight">
                {branding.business_name}
              </span>
            </div>
            <UserMenu />
          </div>

          <div className="space-y-6">
            {/* Header & Back Button */}
            <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
              <div className="flex items-center gap-3">
                <Button variant="outline" size="icon" asChild>
                  <Link to="/cash">
                    <ArrowLeft className="h-4 w-4" />
                  </Link>
                </Button>
                <div>
                  <h1 className="text-2xl font-bold tracking-tight">{workerName}'s Ledger</h1>
                  <p className="text-sm text-muted-foreground">
                    Transaction history, expenses, and top-up audit trail.
                  </p>
                </div>
              </div>

              <Button onClick={() => setIsTopUpOpen(true)}>
                <Plus className="mr-2 h-4 w-4" /> Disburse Cash
              </Button>
            </div>

            {/* Individual Worker KPI Cards */}
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
              <Card>
                <CardHeader className="pb-2">
                  <CardTitle className="text-sm font-medium">Current Balance</CardTitle>
                </CardHeader>
                <CardContent>
                  <div className={`text-2xl font-bold ${balance >= 0 ? "text-emerald-600" : "text-destructive"}`}>
                    ₹{balance.toFixed(2)}
                  </div>
                  <p className="text-xs text-muted-foreground">
                    {balance < 0 ? "Reimbursement due to worker" : "Cash in worker's hand"}
                  </p>
                </CardContent>
              </Card>

              <Card>
                <CardHeader className="pb-2">
                  <CardTitle className="text-sm font-medium text-emerald-600">Total Top-ups (Inflow)</CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="text-2xl font-bold text-emerald-600">+₹{totalCredited.toFixed(2)}</div>
                  <p className="text-xs text-muted-foreground">Total cash disbursed</p>
                </CardContent>
              </Card>

              <Card>
                <CardHeader className="pb-2">
                  <CardTitle className="text-sm font-medium text-rose-500">Total Expenses (Outflow)</CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="text-2xl font-bold text-rose-500">-₹{totalDebited.toFixed(2)}</div>
                  <p className="text-xs text-muted-foreground">Total spent by worker</p>
                </CardContent>
              </Card>
            </div>

            {/* Filters Bar */}
            <Card>
              <CardContent className="p-4 space-y-3">
                <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-4 gap-3">
                  {/* Type Filter */}
                  <div className="space-y-1">
                    <Label className="text-xs">Type</Label>
                    <Select
                      value={typeFilter}
                      onValueChange={(val: any) => setTypeFilter(val)}
                    >
                      <SelectTrigger className="h-9">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="all">All Transactions</SelectItem>
                        <SelectItem value="credit">Incomes (Top-ups)</SelectItem>
                        <SelectItem value="debit">Expenses (Debits)</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>

                  {/* From Date */}
                  <div className="space-y-1">
                    <Label className="text-xs">From Date</Label>
                    <Input
                      type="date"
                      className="h-9"
                      value={fromDate}
                      onChange={(e) => setFromDate(e.target.value)}
                    />
                  </div>

                  {/* To Date */}
                  <div className="space-y-1">
                    <Label className="text-xs">To Date</Label>
                    <Input
                      type="date"
                      className="h-9"
                      value={toDate}
                      onChange={(e) => setToDate(e.target.value)}
                    />
                  </div>

                  {/* Search */}
                  <div className="space-y-1">
                    <Label className="text-xs">Search Notes</Label>
                    <div className="relative">
                      <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
                      <Input
                        placeholder="Search category or notes..."
                        value={searchQuery}
                        onChange={(e) => setSearchQuery(e.target.value)}
                        className="pl-8 h-9"
                      />
                    </div>
                  </div>
                </div>

                {(typeFilter !== "all" || fromDate || toDate || searchQuery) && (
                  <div className="flex justify-end pt-1">
                    <Button variant="ghost" size="sm" onClick={resetFilters} className="text-xs h-7">
                      <RotateCcw className="mr-1 h-3 w-3" /> Reset Filters
                    </Button>
                  </div>
                )}
              </CardContent>
            </Card>

            {/* Transactions Table */}
            <Card className="overflow-hidden">
              <CardContent className="p-0">
                {loading ? (
                  <div className="flex items-center justify-center py-20 text-muted-foreground gap-2">
                    <Loader2 className="h-5 w-5 animate-spin" /> Loading ledger...
                  </div>
                ) : filteredTransactions.length === 0 ? (
                  <div className="text-center py-16 text-muted-foreground text-sm">
                    No transactions match the selected filters.
                  </div>
                ) : (
                  <div className="overflow-x-auto">
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>Date & Time</TableHead>
                          <TableHead>Type</TableHead>
                          <TableHead>Category</TableHead>
                          <TableHead className="hidden md:table-cell">Notes</TableHead>
                          <TableHead className="text-center">Receipt</TableHead>
                          <TableHead className="text-right">Amount</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {filteredTransactions.map((tx) => {
                          const isCredit = tx.type === "credit";
                          return (
                            <TableRow key={tx.id}>
                              <TableCell className="text-xs text-muted-foreground whitespace-nowrap">
                                {new Date(tx.created_at).toLocaleString()}
                              </TableCell>
                              <TableCell>
                                <Badge
                                  variant="secondary"
                                  className={
                                    isCredit
                                      ? "bg-emerald-500/15 text-emerald-600 border-0"
                                      : "bg-rose-500/15 text-rose-500 border-0"
                                  }
                                >
                                  {isCredit ? (
                                    <ArrowDownLeft className="h-3 w-3 mr-1" />
                                  ) : (
                                    <ArrowUpRight className="h-3 w-3 mr-1" />
                                  )}
                                  {isCredit ? "Top-up" : "Expense"}
                                </Badge>
                              </TableCell>
                              <TableCell className="font-medium capitalize text-sm">
                                {tx.category}
                              </TableCell>
                              <TableCell className="hidden md:table-cell text-sm text-muted-foreground max-w-xs truncate">
                                {tx.description ? (
                                  <span className="flex items-center gap-1">
                                    <FileText className="h-3 w-3 shrink-0" />
                                    {tx.description}
                                  </span>
                                ) : (
                                  "—"
                                )}
                              </TableCell>
                              <TableCell className="text-center">
                                {tx.receipt_photo_uri ? (
                                  <a
                                    href={tx.receipt_photo_uri}
                                    target="_blank"
                                    rel="noreferrer"
                                    className="inline-flex items-center justify-center p-1 rounded hover:bg-muted text-primary"
                                    title="View Receipt"
                                  >
                                    <Receipt className="h-4 w-4" />
                                  </a>
                                ) : (
                                  <span className="text-xs text-muted-foreground">—</span>
                                )}
                              </TableCell>
                              <TableCell className={`text-right font-bold text-sm ${isCredit ? "text-emerald-600" : "text-rose-500"}`}>
                                {isCredit ? "+₹" : "-₹"}
                                {tx.amount.toFixed(2)}
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
                  <DialogTitle>Disburse Cash to {workerName}</DialogTitle>
                  <DialogDescription>
                    Credit funds directly to this worker's balance.
                  </DialogDescription>
                </DialogHeader>

                <div className="grid gap-4 py-4">
                  <div className="space-y-1.5">
                    <Label htmlFor="amount">Amount (₹)</Label>
                    <Input
                      id="amount"
                      type="number"
                      step="0.01"
                      min="1"
                      placeholder="e.g. 1500"
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
                      placeholder="e.g. Diesel fuel advance"
                      value={topUpNotes}
                      onChange={(e) => setTopUpNotes(e.target.value)}
                    />
                  </div>
                </div>

                <DialogFooter>
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
        </main>
      </div>
    </SidebarProvider>
  );
}
