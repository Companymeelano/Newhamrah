SET NOCOUNT ON;
SET XACT_ABORT ON;
DECLARE @moname nvarchar(1000) = ?, @cell nvarchar(400) = ?, @tell nvarchar(400) = ?, @addre nvarchar(4000) = ?,
        @cmel nvarchar(200) = ?, @sharh nvarchar(4000) = ?, @vis int = ?, @masir int = ?, @group int = ?,
        @user nvarchar(600) = ?, @date char(10) = ?, @lat float = ?, @lng float = ?, @uid int = ?;
IF EXISTS (SELECT 1 FROM dbo.CUSTOMERS WHERE MONAME = @moname)
BEGIN
    RAISERROR (N'نام تکراري است', 16, 1);
    RETURN;
END
IF NOT EXISTS (SELECT 1 FROM dbo.masir WHERE rdf_masir = @masir)
    SET @masir = (SELECT MIN(rdf_masir) FROM dbo.masir);
IF NOT EXISTS (SELECT 1 FROM dbo.custgroup WHERE group_rdf = @group)
    SET @group = (SELECT MIN(group_rdf) FROM dbo.custgroup);
BEGIN TRANSACTION;
-- Row number inside the route and the customer code, continued the way Atiran numbers them
-- (for example 001001001242 -> 001001001243).
DECLARE @lastCode nvarchar(500), @lastShim int;
SELECT TOP (1) @lastCode = code, @lastShim = sh_i_m
FROM dbo.CUSTOMERS WITH (UPDLOCK, HOLDLOCK)
WHERE RDF_masir = @masir AND sh_i_m IS NOT NULL
ORDER BY sh_i_m DESC, SHMO DESC;
DECLARE @shim int = ISNULL(@lastShim, 0) + 1;
DECLARE @s nvarchar(12) = CAST(@shim AS nvarchar(12));
DECLARE @code nvarchar(500);
IF @lastCode IS NOT NULL AND LEN(@lastCode) > LEN(@s) AND TRY_CONVERT(int, RIGHT(@lastCode, LEN(@s))) = @lastShim
    SET @code = LEFT(@lastCode, LEN(@lastCode) - LEN(@s)) + @s;
ELSE
    SET @code = @s;
INSERT INTO dbo.CUSTOMERS
    (MONAME, code, SHHES, BANKNAME, bankshobe, addre, tell1, tell2, cell, active, cred, man, peygham1, special,
     group_rdf, [date], sh_i_m, sharh, vis_rdf, user_d, defi_vis, hesab_status, maxopen_time, check_eteb, just_naghdi,
     black_list, result_m, c_egh, c_mel, c_pos, kind, IsEmp, MaxManFactor, RDF_masir, Lat, Lng, TafsilCode, Ecode_Vis,
     PersonalityType, EtehadieID, Shenaseh_Egh, TafsilID, Username, [Password], PriceCheck, CheckDateDay, ShmoMoaref,
     RoleCode, TransferCode, CustomerTypeTtmsId, CustomerBranch, TaxInvoiceType, WithTax, FatherName, DateOfBirth)
VALUES
    (@moname, @code, N'', N'ملي', N'', ISNULL(@addre, N''), ISNULL(@tell, N''), N'', ISNULL(@cell, N''), 't', 0, 0, N'', 'f',
     @group, @date, @shim, ISNULL(@sharh, N''), @vis, @user, @vis, 1, '1499/12/29', 1, 0,
     0, N'ذكر نشده', N'', ISNULL(@cmel, N''), N'', ISNULL((SELECT TOP (1) AccType FROM dbo.custgroup WHERE group_rdf = @group), 1), 1, 0, @masir,
     ISNULL(@lat, 0), ISNULL(@lng, 0), N'-1', NULL,
     1, NULL, N'', NULL, N'', N'', 0, 0, 0,
     N'', N'', (SELECT MIN(ID) FROM dbo.CustomerTypeTTMS WHERE ID = 5), N'', 0, 0, N'', NULL);
DECLARE @a int = CAST(SCOPE_IDENTITY() AS int);
-- The same follow-up rows Atiran's own new-customer procedure writes.
INSERT INTO dbo.cus_image (shmo, [image]) VALUES (@a, NULL);
INSERT INTO dbo.cust_act (shmo, [date], act_bes, act_bed, act_dis, act_id, ghno, done_date, t_time, ShowInReport, sysid, isActive, UserID)
VALUES (@a, @date, 0, 0, N'حساب قبلي', 0, 0, @date, GETDATE(), 1, 1, 1, ISNULL(@uid, 1));
INSERT INTO dbo.sys_cus VALUES (1, @a, 1);
IF dbo.IsAccountingSystemStarted() = 1
    EXEC dbo.AssignTafsilCodeToEntity 1, @a;
EXEC dbo.FixManCustomer @a;
COMMIT TRANSACTION;
SELECT @a AS shmo, @code AS code;
