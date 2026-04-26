import argon2 from "argon2";
import { PrismaClient, UserRole } from "../generated/prisma/index.js";

const prisma = new PrismaClient();

const providers = [
  { key: "movieffm", name: "MovieFFM", sortOrder: 1 },
  { key: "777tv", name: "777TV", sortOrder: 2 },
  { key: "dramasq", name: "DramaSQ", sortOrder: 3 },
];

async function seedProviders() {
  await Promise.all(
    providers.map((provider) =>
      prisma.provider.upsert({
        where: { key: provider.key },
        update: {
          name: provider.name,
          sortOrder: provider.sortOrder,
        },
        create: provider,
      }),
    ),
  );
}

async function seedAdmin() {
  const username = "admin";
  const email = "admin@local";
  const adminPassword = process.env.ADMIN_PASSWORD || "admin";
  const passwordHash = await argon2.hash(adminPassword);

  await prisma.user.upsert({
    where: { username },
    update: {
      email,
      role: UserRole.ADMIN,
      displayName: "Administrator",
    },
    create: {
      username,
      email,
      passwordHash,
      role: UserRole.ADMIN,
      displayName: "Administrator",
    },
  });
}

async function main() {
  await seedProviders();
  await seedAdmin();
}

main()
  .then(async () => prisma.$disconnect())
  .catch(async (error) => {
    console.error("Seed failed:", error);
    await prisma.$disconnect();
    process.exit(1);
  });
