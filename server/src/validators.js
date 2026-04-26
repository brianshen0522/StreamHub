import { z } from "zod";

export const loginSchema = z.object({
  login: z.string().trim().min(1),
  password: z.string().min(1),
});

export const createUserSchema = z.object({
  username: z.string().trim().min(3).max(50),
  email: z.string().trim().toLowerCase().email(),
  displayName: z.string().trim().min(1).max(100),
  password: z.string().min(6).max(200),
});

export const updateUserSchema = z.object({
  username: z.string().trim().min(3).max(50).optional(),
  email: z.string().trim().toLowerCase().email().optional(),
  displayName: z.string().trim().min(1).max(100).optional(),
  status: z.enum(["ACTIVE", "DISABLED"]).optional(),
});

export const updateMyProfileSchema = z.object({
  username: z.string().trim().min(3).max(50).optional(),
  email: z.string().trim().toLowerCase().email().optional(),
  displayName: z.string().trim().min(1).max(100).optional(),
});

export const updateMyPasswordSchema = z.object({
  currentPassword: z.string().min(1),
  nextPassword: z.string().min(6).max(200),
});

export const adminResetPasswordSchema = z.object({
  nextPassword: z.string().min(6).max(200),
});

export const toggleProviderSchema = z.object({
  isEnabled: z.boolean(),
});

export const favoriteSchema = z.object({
  providerKey: z.string().trim().min(1),
  mediaType: z.enum(["movie", "tv", "unknown"]).default("unknown"),
  title: z.string().trim().min(1),
  posterUrl: z.string().url().optional().or(z.literal("")).nullable(),
  itemUrl: z.string().url(),
  detailUrl: z.string().url().optional().or(z.literal("")).nullable(),
  seasonUrl: z.string().url().optional().or(z.literal("")).nullable(),
  seasonLabel: z.string().trim().optional().or(z.literal("")).nullable(),
  episodeLabel: z.string().trim().optional().or(z.literal("")).nullable(),
});

export const progressSchema = z.object({
  providerKey: z.string().trim().min(1),
  mediaType: z.enum(["movie", "tv", "unknown"]).default("unknown"),
  title: z.string().trim().min(1),
  posterUrl: z.string().url().optional().or(z.literal("")).nullable(),
  itemUrl: z.string().url(),
  detailUrl: z.string().url().optional().or(z.literal("")).nullable(),
  seasonUrl: z.string().url().optional().or(z.literal("")).nullable(),
  seasonLabel: z.string().trim().optional().or(z.literal("")).nullable(),
  episodeLabel: z.string().trim().optional().or(z.literal("")).nullable(),
  sourceLabel: z.string().trim().optional().or(z.literal("")).nullable(),
  durationSeconds: z.coerce.number().int().min(0).default(0),
  positionSeconds: z.coerce.number().int().min(0).default(0),
  event: z.enum(["progress", "pause", "ended", "switch", "load"]).default("progress"),
});

export const sourcePreferenceSchema = z.object({
  providerKey: z.string().trim().min(1),
  mediaType: z.enum(["movie", "tv", "unknown"]).default("unknown"),
  title: z.string().trim().min(1),
  sourceLabel: z.string().trim().min(1),
});
