export type ToddGlobalStatus =
  | 'IDLE'
  | 'LISTENING'
  | 'THINKING'
  | 'WORKING'
  | 'WAITING'
  | 'LOCAL_ONLY'
  | 'CLOUD_ACTIVE'
  | 'PAUSED'
  | 'OFF'
  | 'ERROR';

export type AIProviderMode = 'LOCAL_ONLY' | 'AUTO' | 'CLOUD_PREFERRED';

export type TaskStatus =
  | 'PLANNED'
  | 'IN_PROGRESS'
  | 'WAITING'
  | 'EXECUTED'
  | 'VERIFYING'
  | 'VERIFIED'
  | 'FAILED'
  | 'BLOCKED'
  | 'PAUSED'
  | 'CANCELLED'
  | 'COMPLETED';

export interface Project {
  id: string;
  name: string;
  description: string;
  repository?: string;
  branch: string;
  lastVerifiedCommit?: string;
  currentGoal: string;
  status: 'ACTIVE' | 'ARCHIVED';
  createdAt: number;
  updatedAt: number;
}

export interface Task {
  id: string;
  projectId: string;
  title: string;
  goal: string;
  userInstructions?: string;
  status: TaskStatus;
  currentStep: string;
  completionCriteria: string;
  lastEvidence?: string;
  failureCause?: string;
  isRecurring?: boolean;
  scheduledTime?: number;
  createdAt: number;
  updatedAt: number;
}

export interface MemoryEntry {
  id: string;
  projectId?: string;
  layer: 'PREFERENCES' | 'PROJECT' | 'TASK' | 'EPISODIC' | 'CONNECTED_SOURCE';
  key: string;
  value: string;
  provenance: 'USER' | 'TOOL' | 'MODEL' | 'INFERENCE';
  isVerified: boolean;
  confidence: number;
  timestamp: number;
}

export interface FailureRecord {
  id: number;
  taskId: string;
  projectId: string;
  operation: string;
  errorMessage: string;
  suspectedCause: string;
  confirmedCause?: string;
  attemptedFix: string;
  retryCondition: string;
  timestamp: number;
}
