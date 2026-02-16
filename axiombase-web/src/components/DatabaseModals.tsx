import { PromptModal } from '@/components/Modal'

interface DatabaseModalsProps {
  deleteTarget: string | null
  setDeleteTarget: (t: string | null) => void
  nukeTarget: string | null
  setNukeTarget: (t: string | null) => void
  nukeTenantTarget: string | null
  setNukeTenantTarget: (t: string | null) => void
  createTenantOpen: boolean
  setCreateTenantOpen: (open: boolean) => void
  onCreateTenant: (id: string) => Promise<void>
}

export default function DatabaseModals({
  createTenantOpen,
  setCreateTenantOpen,
  onCreateTenant,
}: DatabaseModalsProps) {
  return (
    <PromptModal
      open={createTenantOpen}
      onClose={() => setCreateTenantOpen(false)}
      onSubmit={(value) => void onCreateTenant(value)}
      title="Create Tenant"
      description="Enter a unique identifier for the new tenant."
      placeholder="tenant-id"
      submitLabel="Create"
    />
  )
}
