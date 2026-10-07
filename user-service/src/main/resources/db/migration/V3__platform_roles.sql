-- OPERATOR granted no capabilities in Travel Plan. Map it to the least privileged
-- Let's Travel role, matching auth-service; an admin can promote the account afterwards.
UPDATE users SET role = 'TRAVELER' WHERE role = 'OPERATOR';
