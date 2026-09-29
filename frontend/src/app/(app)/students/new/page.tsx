"use client";

import { useRouter } from "next/navigation";

import { StudentForm, emptyStudent } from "@/components/student-form";
import { Card, PageHeader } from "@/components/ui";
import { api } from "@/lib/api";
import type { Student } from "@/lib/types";

export default function NewStudentPage() {
  const router = useRouter();
  return (
    <>
      <PageHeader title="New student" subtitle="Usually you convert a lead instead, which keeps the inquiry history linked." />
      <Card>
        <StudentForm
          initial={emptyStudent()}
          submitLabel="Create student"
          onCancel={() => router.back()}
          onSubmit={async (req) => {
            const s = await api<Student>("/api/students", { body: req });
            router.push(`/students/${s.id}`);
          }}
        />
      </Card>
    </>
  );
}
